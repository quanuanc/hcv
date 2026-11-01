package dev.cheng.vaultstarter;

import java.time.Clock;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.util.Assert;
import org.springframework.vault.core.VaultOperations;

/** Application-facing access to named, independently cached KV secrets. */
public final class VaultSecrets {
    private final Map<String, KvSecret> secrets;

    VaultSecrets(VaultOperations vault, ExpiringSessionManager sessions, VaultProperties properties) {
        var entries = new LinkedHashMap<String, KvSecret>();
        properties.kv().forEach((name, config) -> {
            Assert.hasText(config.mount(), "KV mount must not be empty");
            Assert.hasText(config.path(), "KV path must not be empty");
            Assert.hasText(config.field(), "KV field must not be empty");
            var secret = new KvSecret(vault.opsForVersionedKeyValue(config.mount()), sessions,
                    config.path(), config.field(), Clock.systemUTC(), config.pollInterval());
            secret.get(); // Configured secrets are required; fail startup before serving requests.
            entries.put(name, secret);
        });
        this.secrets = Map.copyOf(entries);
    }

    public KvSecret kv(String name) {
        var secret = secrets.get(name);
        if (secret == null) throw new IllegalArgumentException("Unknown configured KV secret: " + name);
        return secret;
    }

    Collection<KvSecret> all() { return secrets.values(); }
}
