package dev.cheng.vaultstarter;

import java.time.Clock;
import java.time.Duration;
import org.springframework.vault.core.VaultVersionedKeyValueOperations;

/** Injectable provider: callers obtain a current snapshot for each external-service operation. */
public final class KvSecret extends RefreshingVaultSecret<KvSecret.Value> {
    public static final class Value {
        private final String password;
        private final int version;

        private Value(String password, int version) {
            this.password = password;
            this.version = version;
        }

        public String value() { return password; }
        public int version() { return version; }

        @Override
        public String toString() { return "KvSecret.Value[version=" + version + ", password=<redacted>]"; }
    }

    KvSecret(VaultVersionedKeyValueOperations kv, ExpiringSessionManager sessions,
                    String path, String field, Clock clock, Duration pollInterval) {
        super(() -> read(kv, path, field, pollInterval), sessions, clock, pollInterval);
    }

    private static Loaded<Value> read(VaultVersionedKeyValueOperations kv, String path, String field, Duration interval) {
        // Spring Vault handles KV v2's nested data envelope and version metadata.
        var response = kv.get(path);
        if (response == null || response.getData() == null
                || !(response.getData().get(field) instanceof String password) || password.isBlank()
                || !response.getVersion().isVersioned())
            throw new IllegalStateException("Vault returned no valid KV value");
        // KV v2 has versions, not a database rotation TTL. Poll for new versions.
        return new Loaded<>(new Value(password, response.getVersion().getVersion()), interval);
    }
}
