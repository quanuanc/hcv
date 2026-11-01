package dev.cheng.vault_client.vault;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.AbstractDataSource;

/** Sits underneath Hikari: retries physical authentication only, never SQL or transactions. */
final class RefreshingDataSource extends AbstractDataSource {
    private final DataSource delegate;
    private final VaultCredentials credentials;

    RefreshingDataSource(DataSource delegate, VaultCredentials credentials) {
        this.delegate = delegate;
        this.credentials = credentials;
    }

    @Override
    public Connection getConnection() throws SQLException {
        var used = credentials.get();
        try {
            return delegate.getConnection(used.username, used.password);
        } catch (SQLException ex) {
            if (!"28P01".equals(ex.getSQLState())) throw ex;
            try {
                var refreshed = credentials.refreshAfterFailure(used);
                return delegate.getConnection(refreshed.username, refreshed.password);
            } catch (RuntimeException refreshFailure) {
                // Do not attach Vault HTTP responses which might contain sensitive material.
                throw new SQLException("Cannot refresh database credentials from Vault", "08001");
            }
        }
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return getConnection();
    }
}
