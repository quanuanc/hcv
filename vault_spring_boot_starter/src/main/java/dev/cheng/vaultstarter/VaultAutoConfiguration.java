package dev.cheng.vaultstarter;

import java.net.http.HttpClient;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.Assert;
import org.springframework.vault.authentication.ClientCertificateAuthentication;
import org.springframework.vault.authentication.ClientCertificateAuthenticationOptions;
import org.springframework.vault.client.VaultClient;
import org.springframework.vault.client.VaultEndpoint;
import org.springframework.vault.core.VaultOperations;
import org.springframework.vault.core.VaultTemplate;

@AutoConfiguration
@ConditionalOnProperty(prefix = "hcv.vault", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(VaultProperties.class)
public class VaultAutoConfiguration implements DisposableBean {
    private final List<HttpClient> clients = new CopyOnWriteArrayList<>();
    @Bean
    @ConditionalOnMissingBean
    ExpiringSessionManager vaultSessions(SslBundles bundles, VaultProperties properties) {
        Assert.hasText(properties.certRole(), "hcv.vault.cert-role is required");
        var authentication = new ClientCertificateAuthentication(
                ClientCertificateAuthenticationOptions.builder().role(properties.certRole()).build(),
                client(bundles, properties.loginSslBundle(), properties));
        return new ExpiringSessionManager(authentication, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean(VaultOperations.class)
    VaultTemplate vaultTemplate(SslBundles bundles, ExpiringSessionManager sessions, VaultProperties properties) {
        return new VaultTemplate(client(bundles, properties.readSslBundle(), properties), sessions);
    }

    @Bean
    @ConditionalOnMissingBean
    VaultSecrets vaultSecrets(VaultOperations vault, ExpiringSessionManager sessions, VaultProperties properties) {
        return new VaultSecrets(vault, sessions, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    VaultRefreshTask vaultRefreshTask(VaultSecrets registry, ObjectProvider<VaultCredentials> database,
                                      VaultProperties properties) {
        var secrets = new ArrayList<RefreshingVaultSecret<?>>(registry.all());
        database.ifAvailable(secrets::add);
        return new VaultRefreshTask(secrets, properties.refreshTick());
    }

    private VaultClient client(SslBundles bundles, String bundle, VaultProperties properties) {
        Assert.isTrue("https".equalsIgnoreCase(properties.uri().getScheme()), "Vault URI must use HTTPS");
        Assert.isTrue(properties.httpTimeout().toMillis() > 0, "Vault HTTP timeout must be positive");
        var http = HttpClient.newBuilder().sslContext(bundles.getBundle(bundle).createSslContext())
                .connectTimeout(properties.httpTimeout()).build();
        clients.add(http);
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.httpTimeout());
        return VaultClient.builder().endpoint(VaultEndpoint.from(properties.uri())).requestFactory(factory).build();
    }
    @Override
    public void destroy() { clients.forEach(HttpClient::shutdownNow); }
}
