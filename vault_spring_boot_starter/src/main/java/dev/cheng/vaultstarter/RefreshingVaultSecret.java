package dev.cheng.vaultstarter;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.web.client.HttpStatusCodeException;

/** Shared lifecycle for database credentials and KV values. Never logs secret values. */
abstract class RefreshingVaultSecret<T> {
    static final class Loaded<T> {
        final T value;
        final Duration refreshAfter;

        Loaded(T value, Duration refreshAfter) {
            this.value = Objects.requireNonNull(value);
            this.refreshAfter = Objects.requireNonNull(refreshAfter);
            if (refreshAfter.isNegative()) throw new IllegalArgumentException("Negative secret TTL");
        }
    }

    private final Supplier<Loaded<T>> loader;
    private final ExpiringSessionManager sessions;
    private final Clock clock;
    private final Duration pollInterval;
    private T current;
    private Instant nextRead = Instant.MIN;

    RefreshingVaultSecret(Supplier<Loaded<T>> loader, ExpiringSessionManager sessions,
                         Clock clock, Duration pollInterval) {
        if (pollInterval.toMillis() < 1) throw new IllegalArgumentException("Vault poll interval must be positive");
        this.loader = loader;
        this.sessions = sessions;
        this.clock = clock;
        this.pollInterval = pollInterval;
    }

    public synchronized T get() {
        if (current == null) refresh();
        return current;
    }

    synchronized void refreshIfDue() {
        if (!clock.instant().isBefore(nextRead)) refresh();
    }

    public synchronized T refreshAfterFailure(T failed) {
        if (current == failed) refresh();
        return current;
    }

    private void refresh() {
        Instant started = clock.instant();
        Loaded<T> loaded;
        try {
            loaded = loader.get();
        } catch (RuntimeException ex) {
            if (!forbidden(ex)) throw ex;
            sessions.invalidate();
            loaded = loader.get(); // One retry for revoked/expired tokens; shared by all secret types.
        }
        long delay = Math.max(1000, Math.min(pollInterval.toMillis(), loaded.refreshAfter.toMillis()));
        Instant next = started.plusMillis(delay);
        current = loaded.value; // Replace only after a complete, validated read.
        nextRead = next;
    }

    private static boolean forbidden(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause())
            if (cause instanceof HttpStatusCodeException http && http.getStatusCode().value() == 403)
                return true;
        return false;
    }
}
