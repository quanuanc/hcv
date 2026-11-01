package dev.cheng.vaultstarter;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.vault.core.VaultOperations;
import org.springframework.vault.support.VaultResponse;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(classes = dev.cheng.vault_client.VaultClientApplication.class)
class VaultDatabaseIntegrationTests {
    @Autowired VaultCredentials liveCredentials;
    @Autowired VaultOperations liveVault;
    @Value("${spring.datasource.url}") String url;

    @Test
    void stalePasswordRecoversOnNewPhysicalPostgresConnection() throws Exception {
        var actual = liveCredentials.get();
        var stale = new VaultResponse();
        stale.setData(Map.of("username", actual.username, "password", "intentionally-invalid-test-password", "ttl", 30));
        var vault = mock(VaultOperations.class);
        when(vault.read(anyString())).thenReturn(stale)
                .thenAnswer(invocation -> liveVault.read("database/static-creds/hcv-demo"));
        var credentials = new VaultCredentials(vault, mock(ExpiringSessionManager.class),
                "test", Clock.systemUTC(), Duration.ofSeconds(30));
        var datasource = new RefreshingDataSource(new DriverManagerDataSource(url), credentials);
        try (var connection = datasource.getConnection();
             var statement = connection.createStatement();
             var result = statement.executeQuery("select current_user")) {
            assertTrue(result.next());
            assertEquals(actual.username, result.getString(1));
        }
        verify(vault, times(2)).read(anyString());
    }
}
