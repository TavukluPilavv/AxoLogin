package dev.axo.login;

import org.mindrot.jbcrypt.BCrypt;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.Optional;
import java.util.ArrayList;
import java.util.List;

final class AuthStore implements AutoCloseable {
    record Account(String name, String passwordHash, String email, String lastIp, long lastLoginAt) {}
    private final Connection connection;

    AuthStore(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (Statement s = connection.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS accounts (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, password_hash TEXT NOT NULL, email TEXT NOT NULL UNIQUE, last_ip TEXT NOT NULL DEFAULT '', last_login_at INTEGER NOT NULL DEFAULT 0)");
            try { s.executeUpdate("ALTER TABLE accounts ADD COLUMN last_login_at INTEGER NOT NULL DEFAULT 0"); }
            catch (SQLException ignored) { }
            try { s.executeUpdate("ALTER TABLE accounts ADD COLUMN created_ip TEXT NOT NULL DEFAULT ''"); }
            catch (SQLException ignored) { }
            // Existing accounts keep their original known IP as a best-effort migration.
            s.executeUpdate("UPDATE accounts SET created_ip=last_ip WHERE created_ip='' AND last_ip<>''");
        }
    }

    synchronized Optional<Account> get(String uuid) throws SQLException {
        try (PreparedStatement p = connection.prepareStatement("SELECT name,password_hash,email,last_ip,last_login_at FROM accounts WHERE uuid=?")) {
            p.setString(1, uuid);
            try (ResultSet r = p.executeQuery()) {
                return r.next() ? Optional.of(new Account(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getLong(5))) : Optional.empty();
            }
        }
    }

    synchronized void create(String uuid, String name, String password, String email, String ip) throws SQLException {
        try (PreparedStatement p = connection.prepareStatement("INSERT INTO accounts(uuid,name,password_hash,email,last_ip,last_login_at,created_ip) VALUES(?,?,?,?,?,0,?)")) {
            p.setString(1, uuid); p.setString(2, name); p.setString(3, BCrypt.hashpw(password, BCrypt.gensalt(12)));
            p.setString(4, email); p.setString(5, ip); p.setString(6, ip); p.executeUpdate();
        }
    }

    synchronized boolean passwordMatches(Account account, String password) {
        try { return BCrypt.checkpw(password, account.passwordHash()); }
        catch (IllegalArgumentException ex) { return false; }
    }

    synchronized void updateIp(String uuid, String ip) throws SQLException {
        try (PreparedStatement p = connection.prepareStatement("UPDATE accounts SET last_ip=? WHERE uuid=?")) {
            p.setString(1, ip); p.setString(2, uuid); p.executeUpdate();
        }
    }

    synchronized void updateLoginSession(String uuid, String ip, long timestamp) throws SQLException {
        try (PreparedStatement p = connection.prepareStatement("UPDATE accounts SET last_ip=?,last_login_at=? WHERE uuid=?")) {
            p.setString(1, ip); p.setLong(2, timestamp); p.setString(3, uuid); p.executeUpdate();
        }
    }

    synchronized void updatePassword(String uuid, String password) throws SQLException {
        try (PreparedStatement p = connection.prepareStatement("UPDATE accounts SET password_hash=? WHERE uuid=?")) {
            p.setString(1, BCrypt.hashpw(password, BCrypt.gensalt(12)));
            p.setString(2, uuid); p.executeUpdate();
        }
    }

    synchronized List<String> namesByIp(String ip) throws SQLException {
        List<String> names = new ArrayList<>();
        if (ip == null || ip.isBlank()) return names;
        try (PreparedStatement p = connection.prepareStatement("SELECT name FROM accounts WHERE created_ip=? ORDER BY name COLLATE NOCASE")) {
            p.setString(1, ip);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) names.add(r.getString(1));
            }
        }
        return names;
    }

    @Override public void close() throws SQLException { connection.close(); }
}
