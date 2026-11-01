package dev.cheng.vaultstarter;

import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.vault.core.VaultOperations;
import org.springframework.vault.core.VaultVersionedKeyValueOperations;
import org.springframework.vault.support.Versioned;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class VaultAutoConfigurationTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(VaultAutoConfiguration.class, VaultDatabaseAutoConfiguration.class));

    @Test
    void dependencyAloneDoesNotEnableVaultOrCreateDataSource() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(VaultSecrets.class)
                    .doesNotHaveBean(VaultRefreshTask.class).doesNotHaveBean(DataSource.class);
        });
    }

    @Test
    void kvOnlySupportsMultipleNamesAndCustomFieldsWithoutJdbc() {
        var vault = mock(VaultOperations.class);
        var kv = mock(VaultVersionedKeyValueOperations.class);
        when(vault.opsForVersionedKeyValue("test-mount")).thenReturn(kv);
        when(kv.get("email")).thenReturn(Versioned.create(Map.<String, Object>of("password", "test-a"), Versioned.Version.from(1)));
        when(kv.get("api")).thenReturn(Versioned.create(Map.<String, Object>of("api-key", "test-b"), Versioned.Version.from(2)));
        runner.withClassLoader(new FilteredClassLoader("com.zaxxer.hikari", "org.springframework.jdbc"))
                .withBean(VaultOperations.class, () -> vault)
                .withBean(ExpiringSessionManager.class, () -> mock(ExpiringSessionManager.class))
                .withPropertyValues("hcv.vault.enabled=true", "hcv.vault.kv.email.mount=test-mount",
                        "hcv.vault.kv.email.path=email", "hcv.vault.kv.api.mount=test-mount",
                        "hcv.vault.kv.api.path=api", "hcv.vault.kv.api.field=api-key")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(VaultSecrets.class)
                            .hasSingleBean(VaultRefreshTask.class).doesNotHaveBean(DataSource.class);
                    var secrets = context.getBean(VaultSecrets.class);
                    assertThat(secrets.kv("email").get().value()).isEqualTo("test-a");
                    assertThat(secrets.kv("api").get().version()).isEqualTo(2);
                    assertThat(secrets.kv("api").get().value()).isEqualTo("test-b");
                });
    }

    @Test
    void respectsApplicationDataSourceEvenWhenDatabaseIntegrationIsEnabled() {
        var datasource = mock(DataSource.class);
        var vault = mock(VaultOperations.class);
        runner.withBean(VaultOperations.class, () -> vault)
                .withBean(ExpiringSessionManager.class, () -> mock(ExpiringSessionManager.class))
                .withBean(DataSource.class, () -> datasource)
                .withPropertyValues("hcv.vault.enabled=true", "hcv.vault.database.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(DataSource.class)
                            .doesNotHaveBean(VaultCredentials.class);
                    assertThat(context.getBean(DataSource.class)).isSameAs(datasource);
                    verifyNoInteractions(vault);
                });
    }

    @Test
    void configuredButMissingSecretFailsStartup() {
        var vault = mock(VaultOperations.class);
        var kv = mock(VaultVersionedKeyValueOperations.class);
        when(vault.opsForVersionedKeyValue("test-mount")).thenReturn(kv);
        runner.withBean(VaultOperations.class, () -> vault)
                .withBean(ExpiringSessionManager.class, () -> mock(ExpiringSessionManager.class))
                .withPropertyValues("hcv.vault.enabled=true", "hcv.vault.kv.missing.mount=test-mount",
                        "hcv.vault.kv.missing.path=missing")
                .run(context -> assertThat(context).hasFailed());
    }
}
