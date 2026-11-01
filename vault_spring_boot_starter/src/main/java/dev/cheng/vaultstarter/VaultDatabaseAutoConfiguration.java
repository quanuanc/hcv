package dev.cheng.vaultstarter;

import java.time.Clock;
import javax.sql.DataSource;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.util.Assert;
import org.springframework.vault.core.VaultOperations;

@AutoConfiguration(after = VaultAutoConfiguration.class,
        beforeName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@ConditionalOnClass({HikariDataSource.class, DriverManagerDataSource.class})
@ConditionalOnProperty(prefix = "hcv.vault", name = {"enabled", "database.enabled"}, havingValue = "true")
@ConditionalOnMissingBean(DataSource.class)
public class VaultDatabaseAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    VaultCredentials vaultCredentials(VaultOperations vault, ExpiringSessionManager sessions, VaultProperties properties) {
        Assert.hasText(properties.database().path(), "hcv.vault.database.path is required");
        return new VaultCredentials(vault, sessions, properties.database().path(), Clock.systemUTC(),
                properties.database().pollInterval());
    }

    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource dataSource(VaultCredentials credentials, VaultProperties properties,
                               @Value("${spring.datasource.url}") String url) {
        credentials.get();
        var pool = new HikariDataSource();
        pool.setDataSource(new RefreshingDataSource(new DriverManagerDataSource(url), credentials,
                properties.database().authenticationSqlState()));
        return pool;
    }
}
