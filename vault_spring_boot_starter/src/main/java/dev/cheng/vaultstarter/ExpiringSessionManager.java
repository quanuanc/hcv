package dev.cheng.vaultstarter;

import java.time.Clock;
import java.time.Instant;
import org.springframework.vault.authentication.ClientAuthentication;
import org.springframework.vault.authentication.LoginToken;
import org.springframework.vault.authentication.SessionManager;
import org.springframework.vault.support.VaultToken;

/** TTL-based re-login for the read-only policy which cannot renew or revoke tokens. */
final class ExpiringSessionManager implements SessionManager {
    private final ClientAuthentication authentication;
    private final Clock clock;
    private VaultToken token;
    private Instant expires = Instant.MIN;

    ExpiringSessionManager(ClientAuthentication authentication, Clock clock) {
        this.authentication = authentication;
        this.clock = clock;
    }

    @Override
    public synchronized VaultToken getSessionToken() {
        if (token == null || !clock.instant().isBefore(expires)) {
            Instant started = clock.instant();
            LoginToken login = (LoginToken) authentication.login();
            long ttl = login.getLeaseDuration().toMillis();
            if (ttl <= 0) throw new IllegalStateException("Vault login returned no positive TTL");
            expires = started.plusMillis(ttl - Math.min(5000, ttl / 10));
            token = login;
        }
        return token;
    }

    synchronized void invalidate() {
        token = null;
    }
}
