package dev.cheng.vaultstarter;

import java.time.Clock;
import java.time.Duration;
import org.springframework.vault.core.VaultOperations;

/** Database-specific response validation; lifecycle is shared with other Vault secrets. */
final class VaultCredentials extends RefreshingVaultSecret<VaultCredentials.Credentials> {
    static final class Credentials {
        final String username;
        final String password;
        Credentials(String username, String password) {
            this.username = username;
            this.password = password;
        }
    }

    VaultCredentials(VaultOperations vault, ExpiringSessionManager sessions, String path,
                     Clock clock, Duration pollInterval) {
        super(() -> read(vault, path), sessions, clock, pollInterval);
    }

    private static Loaded<Credentials> read(VaultOperations vault, String path) {
        var response = vault.read(path);
        if (response == null || response.getData() == null)
            throw new IllegalStateException("Vault returned no static credentials");
        var data = response.getData();
        if (!(data.get("username") instanceof String user) || user.isBlank()
                || !(data.get("password") instanceof String password) || password.isEmpty()
                || !(data.get("ttl") instanceof Number ttl) || ttl.longValue() < 0)
            throw new IllegalStateException("Vault returned invalid static credentials");
        return new Loaded<>(new Credentials(user, password), Duration.ofSeconds(ttl.longValue()));
    }
}
