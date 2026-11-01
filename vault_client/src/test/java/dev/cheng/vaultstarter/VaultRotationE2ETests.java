package dev.cheng.vaultstarter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Clock;
import java.util.function.BooleanSupplier;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

/** Real scheduled rotations; run with ../vault_server/verify-client-rotation.py. */
@EnabledIfSystemProperty(named = "hcv.rotation.e2e", matches = "true")
@SpringBootTest(classes = dev.cheng.vault_client.VaultClientApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.minimum-idle=0",
        "spring.datasource.hikari.connection-timeout=10000"
})
class VaultRotationE2ETests {
    @Autowired HikariDataSource pool;
    @Autowired VaultCredentials credentials;
    @Autowired VaultSecrets secrets;
    @Autowired VaultProperties properties;
    @Autowired ExpiringSessionManager sessions;
    @Autowired SslBundles bundles;
    @Autowired VaultRefreshTask refreshTask;
    @Value("${hcv.vault.uri}") String uri;
    @Value("${spring.datasource.url}") String jdbcUrl;
    @LocalServerPort int port;

    @Test
    @org.junit.jupiter.api.Timeout(180)
    void survivesRealRotationsAndTokenExpiry() throws Exception {
        // Independent observer: its reads must not refresh the application's Token or credentials.
        var configuration = new VaultAutoConfiguration();
        var observerSessions = configuration.vaultSessions(bundles, properties);
        var observerVault = configuration.vaultTemplate(bundles, observerSessions, properties);
        var observer = new VaultCredentials(observerVault, observerSessions,
                "database/static-creds/hcv-demo", Clock.systemUTC(), Duration.ofSeconds(1));
        var initialToken = ReflectionTestUtils.getField(sessions, "token");
        var servicePassword = secrets.kv("demo-service");
        var kvObserver = new KvSecret(observerVault.opsForVersionedKeyValue("app-secrets"), observerSessions,
                "demo-service", "password", Clock.systemUTC(), Duration.ofSeconds(1));
        for (int round = 1; round <= 2; round++) {
            var previous = servicePassword.get();
            // The admin-side Python runner updates KV only after seeing this ready marker.
            System.out.println("E2E KV ready " + round);
            await(() -> servicePassword.get().version() > previous.version(), 15,
                    "Shared scheduler did not retrieve the new KV version");
            var expected = kvObserver.refreshAfterFailure(kvObserver.get());
            var actual = servicePassword.get();
            assertTrue(actual.value().equals(expected.value()), "Loaded KV password must match Vault");
            assertFalse(actual.value().equals(previous.value()), "KV password must really change");
            try (var http = HttpClient.newHttpClient()) {
                var response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/api/demo-service/secret-status")).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                assertEquals("{\"version\":" + actual.version() + "}", response.body());
                assertFalse(response.body().contains(actual.value()));
            }
            System.out.println("E2E KV update " + round + " observed; version=" + actual.version() + "; status HTTP=200");
        }
        var baseline = observer.get();
        int pid = queryPid();
        System.out.println("E2E baseline PostgreSQL PID=" + pid);

        for (int round = 1; round <= 3; round++) {
            var previous = baseline;
            await(() -> {
                observer.refreshAfterFailure(observer.get());
                return !observer.get().password.equals(previous.password);
            }, 45, "Vault did not automatically rotate its password");
            baseline = observer.get();
            var latest = baseline;
            // No manual refresh: prove the production @Scheduled task updates the cache.
            await(() -> credentials.get().password.equals(latest.password), 8,
                    "Scheduled client refresh did not observe the rotation");
            var oldConnection = new DriverManagerDataSource(jdbcUrl);
            SQLException rejected = assertThrows(SQLException.class, () -> {
                try (var ignored = oldConnection.getConnection(previous.username, previous.password)) {
                    fail("The previous password still authenticates");
                }
            });
            assertEquals("28P01", rejected.getSQLState());
            pool.getHikariPoolMXBean().softEvictConnections();
            int nextPid = queryPid();
            assertNotEquals(pid, nextPid, "Must establish a new physical connection");
            pid = nextPid;
            assertUsersEndpoint();
            System.out.println("E2E scheduled rotation " + round
                    + ": old password rejected; background cache refreshed; new PID=" + pid + "; HTTP users=200");
        }

        // Deliberately stop proactive refresh for one rotation to exercise the actual stale-password path.
        refreshTask.destroy();
        try {
            var stale = credentials.get();
            await(() -> {
                observer.refreshAfterFailure(observer.get());
                return !observer.get().password.equals(stale.password);
            }, 45, "No rotation while proactive refresh was paused");
            assertSame(stale, credentials.get(), "Cache must still be stale before connecting");
            pool.getHikariPoolMXBean().softEvictConnections();
            int nextPid = queryPid();
            assertNotEquals(pid, nextPid);
            assertFalse(stale.password.equals(credentials.get().password),
                    "Connection authentication failure must refresh credentials");
            assertUsersEndpoint();
            System.out.println("E2E stale-cache recovery: new PID=" + nextPid + "; HTTP users=200");
        } finally {
            refreshTask.afterPropertiesSet();
        }
        assertTrue(!initialToken.equals(ReflectionTestUtils.getField(sessions, "token")),
                "Application must re-login after the real 20-second Token TTL");
        System.out.println("E2E Token changed after real expiration; all four real rotations passed.");
    }

    private int queryPid() throws Exception {
        try (var connection = pool.getConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery("select pg_backend_pid()")) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private void assertUsersEndpoint() throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            var response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/users"))
                    .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("zhangsan@example.com"));
        }
    }

    private static void await(BooleanSupplier condition, int seconds, String failure) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(200);
        }
        fail(failure);
    }
}
