package dev.cheng.vaultstarter;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Configuration contains secret locations, never secret values. */
@ConfigurationProperties("hcv.vault")
public record VaultProperties(
        boolean enabled,
        @DefaultValue("https://127.0.0.1:8200") URI uri,
        String certRole,
        @DefaultValue("vault-login") String loginSslBundle,
        @DefaultValue("vault-read") String readSslBundle,
        @DefaultValue("5s") Duration httpTimeout,
        @DefaultValue("1s") Duration refreshTick,
        @DefaultValue Database database,
        Map<String, Kv> kv) {
    public VaultProperties {
        kv = kv == null ? Map.of() : Map.copyOf(kv);
    }

    public record Database(boolean enabled, String path,
            @DefaultValue("30s") Duration pollInterval,
            @DefaultValue("28P01") String authenticationSqlState) {}

    public record Kv(String mount, String path,
            @DefaultValue("password") String field,
            @DefaultValue("5s") Duration pollInterval) {}
}
