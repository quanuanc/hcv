package dev.cheng.vaultstarter;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.vault.core.VaultVersionedKeyValueOperations;
import org.springframework.vault.support.Versioned;
import org.springframework.web.client.HttpClientErrorException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KvSecretTests {
    private final Clock clock = mock(Clock.class);
    private final VaultVersionedKeyValueOperations kv = mock(VaultVersionedKeyValueOperations.class);
    private final ExpiringSessionManager sessions = mock(ExpiringSessionManager.class);

    KvSecretTests() {
        when(clock.instant()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
    }

    private KvSecret provider() {
        return new KvSecret(kv, sessions, "demo-service", "password", clock, Duration.ofSeconds(5));
    }

    private static Versioned<Map<String, Object>> value(String password, int version) {
        return Versioned.create(Map.of("password", password), Versioned.Version.from(version));
    }

    @Test
    void usesSharedPollingCacheAndPreservesLastValidValueOnDeletion() {
        when(kv.get("demo-service")).thenReturn(value("first-test-password", 1), value("second-test-password", 2), null);
        var provider = provider();
        var first = provider.get();
        assertEquals(1, first.version());
        assertFalse(first.toString().contains(first.value()));
        provider.refreshIfDue();
        verify(kv).get("demo-service");
        when(clock.instant()).thenReturn(Instant.parse("2026-01-01T00:00:06Z"));
        provider.refreshIfDue();
        assertEquals(2, provider.get().version());
        assertEquals("second-test-password", provider.get().value());
        when(clock.instant()).thenReturn(Instant.parse("2026-01-01T00:00:12Z"));
        assertThrows(IllegalStateException.class, provider::refreshIfDue);
        assertEquals(2, provider.get().version());
    }

    @Test
    void kv403UsesTheSameSessionInvalidationAndRetryAsDatabase() {
        when(kv.get("demo-service")).thenThrow(HttpClientErrorException.create(
                HttpStatus.FORBIDDEN, "Forbidden", HttpHeaders.EMPTY, new byte[0], null))
                .thenReturn(value("test-password", 3));
        assertEquals(3, provider().get().version());
        verify(sessions).invalidate();
        verify(kv, times(2)).get("demo-service");
    }

    @Test
    void rejectsInvalidKvValueAtStartup() {
        when(kv.get("demo-service")).thenReturn(value("", 1));
        assertThrows(IllegalStateException.class, () -> provider().get());
    }

    @Test
    void failingDatabaseRefreshDoesNotSkipKvRefresh() {
        var database = mock(VaultCredentials.class);
        var service = mock(KvSecret.class);
        doThrow(new IllegalStateException("offline")).when(database).refreshIfDue();
        new VaultRefreshTask(List.of(database, service), Duration.ofSeconds(1)).refresh();
        verify(service).refreshIfDue();
    }
}
