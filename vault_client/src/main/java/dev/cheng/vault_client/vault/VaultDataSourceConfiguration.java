package dev.cheng.vault_client.vault;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.vault.authentication.ClientCertificateAuthentication;
import org.springframework.vault.authentication.ClientCertificateAuthenticationOptions;
import org.springframework.vault.client.VaultClient;
import org.springframework.vault.client.VaultEndpoint;
import org.springframework.vault.core.VaultTemplate;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class VaultDataSourceConfiguration {
    @Bean
    ExpiringSessionManager vaultSessions(SslBundles bundles,
            @Value("${app.vault.uri}") String uri,
            @Value("${app.vault.cert-role}") String role) {
        var authentication = new ClientCertificateAuthentication(
                ClientCertificateAuthenticationOptions.builder().role(role).build(),
                client(bundles, "vault-login", uri));
        return new ExpiringSessionManager(authentication, Clock.systemUTC());
    }

    @Bean
    VaultCredentials vaultCredentials(SslBundles bundles, ExpiringSessionManager sessions,
            @Value("${app.vault.uri}") String uri,
            @Value("${app.vault.credentials-path}") String path,
            @Value("${app.vault.poll-interval:30s}") Duration interval) {
        return new VaultCredentials(new VaultTemplate(client(bundles, "vault-read", uri), sessions),
                sessions, path, Clock.systemUTC(), interval);
    }

    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource dataSource(VaultCredentials credentials,
            @Value("${spring.datasource.url}") String url) {
        credentials.get(); // Fail startup before Flyway/JPA when Vault is unavailable.
        var physical = new DriverManagerDataSource(url);
        var pool = new HikariDataSource();
        pool.setDataSource(new RefreshingDataSource(physical, credentials));
        return pool;
    }

    @Bean
    VaultRefreshTask vaultRefreshTask(VaultCredentials credentials) {
        return new VaultRefreshTask(credentials);
    }

    static final class VaultRefreshTask {
        private final VaultCredentials credentials;

        VaultRefreshTask(VaultCredentials credentials) {
            this.credentials = credentials;
        }

        @Scheduled(fixedDelay = 1000)
        public void refresh() {
            try {
                credentials.refreshIfDue();
            } catch (RuntimeException ex) {
                LoggerFactory.getLogger(VaultDataSourceConfiguration.class)
                        .warn("Vault credential refresh failed; retaining last credentials and retrying");
            }
        }
    }

    private static VaultClient client(SslBundles bundles, String bundle, String uri) {
        var http = HttpClient.newBuilder().sslContext(bundles.getBundle(bundle).createSslContext())
                .connectTimeout(Duration.ofSeconds(5)).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(5));
        return VaultClient.builder().endpoint(VaultEndpoint.from(uri)).requestFactory(factory).build();
    }
}
