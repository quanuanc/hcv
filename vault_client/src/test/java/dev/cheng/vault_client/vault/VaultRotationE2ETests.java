package dev.cheng.vault_client.vault;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Duration;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real scheduled rotations; run with ../vault_server/verify-client-rotation.py. */
@EnabledIfSystemProperty(named = "hcv.rotation.e2e", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.minimum-idle=0",
        "spring.datasource.hikari.connection-timeout=10000"
})
class VaultRotationE2ETests {
    @Autowired HikariDataSource pool;
    @Autowired VaultCredentials credentials;
    @Autowired ExpiringSessionManager sessions;
    @Autowired SslBundles bundles;
    @MockitoSpyBean VaultDataSourceConfiguration.VaultRefreshTask refreshTask;
    @Value("${app.vault.uri}") String uri;
    @Value("${spring.datasource.url}") String jdbcUrl;
    @LocalServerPort int port;

    @Test
    void survivesRealRotationsAndTokenExpiry() throws Exception {
        // Independent observer: its reads must not refresh the application's Token or credentials.
        var configuration = new VaultDataSourceConfiguration();
        var observerSessions = configuration.vaultSessions(bundles, uri, "spring-boot-demo");
        var observer = configuration.vaultCredentials(bundles, observerSessions, uri,
                "database/static-creds/hcv-demo", Duration.ofSeconds(1));
        var initialToken = ReflectionTestUtils.getField(sessions, "token");
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
        doNothing().when(refreshTask).refresh();
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
            doCallRealMethod().when(refreshTask).refresh();
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
