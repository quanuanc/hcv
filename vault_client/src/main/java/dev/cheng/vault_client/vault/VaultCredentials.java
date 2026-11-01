package dev.cheng.vault_client.vault;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.vault.core.VaultOperations;
import org.springframework.web.client.HttpStatusCodeException;

/** Serializes refreshes; credentials and exceptions containing responses are never logged. */
final class VaultCredentials {
    static final class Credentials {
        final String username;
        final String password;
        Credentials(String username, String password) {
            this.username = username;
            this.password = password;
        }
    }

    private final VaultOperations vault;
    private final ExpiringSessionManager sessions;
    private final String path;
    private final Clock clock;
    private final Duration pollInterval;
    private Credentials current;
    private Instant nextRead = Instant.MIN;

    VaultCredentials(VaultOperations vault, ExpiringSessionManager sessions, String path,
                     Clock clock, Duration pollInterval) {
        if (pollInterval.isZero() || pollInterval.isNegative())
            throw new IllegalArgumentException("Vault poll interval must be positive");
        this.vault = vault;
        this.sessions = sessions;
        this.path = path;
        this.clock = clock;
        this.pollInterval = pollInterval;
    }

    synchronized Credentials get() {
        if (current == null) refresh();
        return current;
    }

    synchronized void refreshIfDue() {
        if (!clock.instant().isBefore(nextRead)) refresh();
    }

    synchronized Credentials refreshAfterFailure(Credentials failed) {
        if (current == failed) refresh();
        return current;
    }

    private void refresh() {
        Instant started = clock.instant();
        Map<String, Object> data;
        try {
            data = read();
        } catch (RuntimeException ex) {
            if (!forbidden(ex)) throw ex;
            sessions.invalidate();
            data = read(); // One retry also handles externally revoked tokens.
        }
        if (!(data.get("username") instanceof String user) || user.isBlank()
                || !(data.get("password") instanceof String password) || password.isEmpty()
                || !(data.get("ttl") instanceof Number ttl) || ttl.longValue() < 0)
            throw new IllegalStateException("Vault returned invalid static credentials");
        current = new Credentials(user, password);
        long delay = Math.max(1000, Math.min(pollInterval.toMillis(), ttl.longValue() * 1000));
        nextRead = started.plusMillis(delay);
    }

    private Map<String, Object> read() {
        var response = vault.read(path);
        if (response == null || response.getData() == null)
            throw new IllegalStateException("Vault returned no static credentials");
        return response.getData();
    }

    private static boolean forbidden(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause())
            if (cause instanceof HttpStatusCodeException http && http.getStatusCode().value() == 403)
                return true;
        return false;
    }
}
