package dev.cheng.vaultstarter;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.vault.authentication.ClientAuthentication;
import org.springframework.vault.authentication.LoginToken;
import org.springframework.vault.core.VaultOperations;
import org.springframework.vault.support.VaultResponse;
import org.springframework.web.client.HttpClientErrorException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VaultCredentialsTests {
    private final Clock clock = mock(Clock.class);
    private final VaultOperations vault = mock(VaultOperations.class);
    private final ExpiringSessionManager sessions = mock(ExpiringSessionManager.class);

    VaultCredentialsTests() {
        when(clock.instant()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
    }

    static VaultResponse response(String password, long ttl) {
        var response = new VaultResponse();
        response.setData(Map.of("username", "user", "password", password, "ttl", ttl));
        return response;
    }

    private VaultCredentials provider() {
        return new VaultCredentials(vault, sessions, "database/static-creds/test", clock, Duration.ofSeconds(30));
    }

    @Test
    void reloginsBeforeTokenExpiryWithoutRenewal() {
        var authentication = mock(ClientAuthentication.class);
        var first = LoginToken.of("first".toCharArray(), Duration.ofSeconds(100));
        var second = LoginToken.of("second".toCharArray(), Duration.ofSeconds(100));
        when(authentication.login()).thenReturn(first, second);
        var manager = new ExpiringSessionManager(authentication, clock);
        assertSame(first, manager.getSessionToken());
        assertSame(first, manager.getSessionToken());
        when(clock.instant()).thenReturn(Instant.parse("2026-01-01T00:01:36Z"));
        assertSame(second, manager.getSessionToken());
        verify(authentication, times(2)).login();
    }

    @Test
    void refreshesAtTtlAndKeepsCredentialsDuringOutage() {
        when(vault.read(anyString())).thenReturn(response("old", 5), response("new", 30))
                .thenThrow(new IllegalStateException("offline"));
        var provider = provider();
        assertEquals("old", provider.get().password);
        provider.refreshIfDue();
        verify(vault).read(anyString());
        when(clock.instant()).thenReturn(Instant.parse("2026-01-01T00:00:06Z"));
        provider.refreshIfDue();
        assertEquals("new", provider.get().password);
        when(clock.instant()).thenReturn(Instant.parse("2026-01-01T00:01:00Z"));
        assertThrows(IllegalStateException.class, provider::refreshIfDue);
        assertEquals("new", provider.get().password);
    }

    @Test
    void forbiddenReadInvalidatesSessionAndRetriesOnce() {
        when(vault.read(anyString())).thenThrow(HttpClientErrorException.create(
                HttpStatus.FORBIDDEN, "Forbidden", HttpHeaders.EMPTY, new byte[0], null))
                .thenReturn(response("new", 30));
        assertEquals("new", provider().get().password);
        verify(sessions).invalidate();
        verify(vault, times(2)).read(anyString());
    }

    @Test
    void retriesOnlyPasswordFailureAndCoalescesStaleReaders() throws Exception {
        when(vault.read(anyString())).thenReturn(response("old", 30), response("new", 30));
        var provider = provider();
        var stale = provider.get();
        var delegate = mock(DataSource.class);
        var connection = mock(Connection.class);
        when(delegate.getConnection("user", "old")).thenThrow(new SQLException("bad password", "28P01"));
        when(delegate.getConnection("user", "new")).thenReturn(connection);
        assertSame(connection, new RefreshingDataSource(delegate, provider).getConnection());
        assertEquals("new", provider.refreshAfterFailure(stale).password);
        verify(vault, times(2)).read(anyString());
        when(delegate.getConnection("user", "new")).thenThrow(new SQLException("network", "08001"));
        assertThrows(SQLException.class, () -> new RefreshingDataSource(delegate, provider).getConnection());
        verify(vault, times(2)).read(anyString());
    }

    @Test
    void stopsAfterSecondAuthenticationFailure() throws Exception {
        when(vault.read(anyString())).thenReturn(response("old", 30), response("new", 30));
        var delegate = mock(DataSource.class);
        when(delegate.getConnection(anyString(), anyString())).thenThrow(new SQLException("bad password", "28P01"));
        assertThrows(SQLException.class, () -> new RefreshingDataSource(delegate, provider()).getConnection());
        verify(delegate, times(2)).getConnection(anyString(), anyString());
        verify(vault, times(2)).read(anyString());
    }
}
