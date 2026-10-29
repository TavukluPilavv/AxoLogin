package dev.axo.login.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import org.mindrot.jbcrypt.BCrypt;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.sql.*;
import java.nio.file.Files;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Pattern;

@Plugin(id = "axovelocity", name = "AxoVelocity", version = "1.0.0", authors = {"Axo"})
public final class AxoLoginVelocity {
    private static final String LOGIN = "login";
    private static final String LOBBY = "lobby";
    private static final long SESSION_MILLIS = 2L * 60L * 60L * 1000L;
    private static final Pattern EMAIL = Pattern.compile("(?i)^[A-Z0-9._%+-]+@gmail\\.com$");
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final Set<UUID> authenticated = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> attempts = new ConcurrentHashMap<>();
    private final Map<UUID, Long> deadlines = new ConcurrentHashMap<>();
    private AuthStore store;

    @Inject
    public AxoVelocityVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        try {
            Files.createDirectories(dataDirectory);
            store = new AuthStore(dataDirectory.resolve("accounts.db"));
        } catch (Exception e) {
            logger.severe("AxoVelocity database could not be opened: " + e.getMessage());
            return;
        }
        proxy.getCommandManager().register(
                proxy.getCommandManager().metaBuilder("register").aliases("kayit", "kayıt").plugin(this).build(),
                new AuthCommand("register"));
        proxy.getCommandManager().register(
                proxy.getCommandManager().metaBuilder("login").aliases("giris", "giriş").plugin(this).build(),
                new AuthCommand("login"));
        proxy.getCommandManager().register(
                proxy.getCommandManager().metaBuilder("forgot").plugin(this).build(),
                new AuthCommand("forgot"));
        logger.info("AxoVelocity Velocity enabled. Players must authenticate before entering other servers.");
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        String server = event.getServer().getServerInfo().getName();
        if (server.equalsIgnoreCase(LOGIN)) {
            authenticated.remove(player.getUniqueId());
            attempts.put(player.getUniqueId(), 0);
            deadlines.put(player.getUniqueId(), System.currentTimeMillis() + 120_000L);
            try {
                Optional<Account> account = store.get(player.getUniqueId().toString());
                long now = System.currentTimeMillis();
                String ip = ipOf(player);
                if (account.isPresent() && !ip.isBlank() && ip.equals(account.get().lastIp())
                        && account.get().lastLoginAt() > 0
                        && now - account.get().lastLoginAt() < SESSION_MILLIS) {
                    authenticate(player, true);
                    return;
                }
                if (account.isPresent()) {
                    player.sendMessage(Component.text("§bAxoVelocity §8» §fHesabınız kayıtlı. §a/login <şifre>"));
                } else {
                    player.sendMessage(Component.text("§bAxoVelocity §8» §fKayıt olmak için §a/register <şifre> <şifre-tekrar> <Gmail>"));
                }
            } catch (Exception e) {
                logger.warning("Account lookup failed: " + e.getMessage());
                player.sendMessage(Component.text("§cGiriş sistemi veritabanına erişemedi. Yetkiliye bildirin."));
            }
        }
    }

    @Subscribe
    public void onPreConnect(ServerPreConnectEvent event) {
        Player player = event.getPlayer();
        String target = event.getResult().getServer().map(s -> s.getServerInfo().getName()).orElse("");
        if (target.equalsIgnoreCase(LOGIN)) return;
        if (!authenticated.contains(player.getUniqueId())) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            player.sendMessage(Component.text("§cÖnce Login sunucusunda giriş yapmalısınız."));
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        authenticated.remove(id);
        attempts.remove(id);
        deadlines.remove(id);
    }

    private void authenticate(Player player, boolean sessionLogin) {
        UUID id = player.getUniqueId();
        authenticated.add(id);
        attempts.remove(id);
        deadlines.remove(id);
        try {
            if (!sessionLogin) store.updateLoginSession(id.toString(), ipOf(player), System.currentTimeMillis());
        } catch (Exception e) {
            logger.warning("Could not save login session: " + e.getMessage());
        }
        player.sendMessage(Component.text("§aAxoVelocity §8» §fGiriş başarılı! Lobby'ye aktarılıyorsunuz."));
        proxy.getServer(LOBBY).ifPresentOrElse(server ->
                player.createConnectionRequest(server).connect().thenAccept(result -> {
                    if (!result.isSuccessful()) player.sendMessage(Component.text("§cLobby bağlantısı başarısız. Lobby sunucusu açık mı?"));
                }),
                () -> player.sendMessage(Component.text("§cLobby sunucusu Velocity yapılandırmasında bulunamadı.")));
    }

    private final class AuthCommand implements SimpleCommand {
        private final String type;
        private AuthCommand(String type) { this.type = type; }

        @Override
        public void execute(Invocation invocation) {
            if (!(invocation.source() instanceof Player player)) {
                invocation.source().sendMessage(Component.text("Bu komut oyuncular içindir."));
                return;
            }
            if (type.equals("forgot")) {
                player.sendMessage(Component.text("§bAxoVelocity §8» §fŞifre sıfırlama için yetkili ekiple iletişime geçin."));
                return;
            }
            UUID id = player.getUniqueId();
            if (!player.getCurrentServer().map(c -> c.getServer().getServerInfo().getName().equalsIgnoreCase(LOGIN)).orElse(false)) {
                player.sendMessage(Component.text("§cBu komutu Login sunucusunda kullanın."));
                return;
            }
            String[] args = invocation.arguments();
            try {
                Optional<Account> account = store.get(id.toString());
                if (type.equals("register")) {
                    if (account.isPresent()) { player.sendMessage(Component.text("§cHesabınız kayıtlı. /login <şifre>")); return; }
                    if (args.length != 3) { player.sendMessage(Component.text("§eKullanım: /register <şifre> <şifre-tekrar> <Gmail>")); return; }
                    if (args[0].length() < 8 || args[0].length() > 72) { player.sendMessage(Component.text("§cŞifre 8-72 karakter olmalıdır.")); return; }
                    if (!args[0].equals(args[1])) { player.sendMessage(Component.text("§cŞifreler aynı değil.")); return; }
                    if (!EMAIL.matcher(args[2]).matches()) { player.sendMessage(Component.text("§cGeçerli bir @gmail.com adresi girin.")); return; }
                    store.create(id.toString(), player.getUsername(), args[0], args[2].toLowerCase(java.util.Locale.ROOT), ipOf(player));
                    authenticate(player, false);
                } else {
                    if (account.isEmpty()) { player.sendMessage(Component.text("§cHesabınız kayıtlı değil. /register <şifre> <şifre-tekrar> <Gmail>")); return; }
                    if (args.length != 1) { player.sendMessage(Component.text("§eKullanım: /login <şifre>")); return; }
                    if (!store.passwordMatches(account.get(), args[0])) {
                        int count = attempts.merge(id, 1, Integer::sum);
                        player.sendMessage(Component.text("§cŞifre yanlış. Kalan deneme: " + Math.max(0, 3-count)));
                        if (count >= 3) player.disconnect(Component.text("Çok fazla hatalı giriş. Tekrar bağlanın."));
                        return;
                    }
                    authenticate(player, false);
                }
            } catch (SQLException e) {
                logger.warning("Authentication database error: " + e.getMessage());
                player.sendMessage(Component.text("§cİşlem başarısız. Yetkiliye bildirin."));
            }
        }

        @Override
        public boolean hasPermission(Invocation invocation) { return true; }
    }

    private static String ipOf(Player player) {
        if (player.getRemoteAddress() instanceof InetSocketAddress address && address.getAddress() != null)
            return address.getAddress().getHostAddress();
        return player.getRemoteAddress().getHostString();
    }

    private static final class AuthStore implements AutoCloseable {
        private final Connection connection;
        AuthStore(Path file) throws Exception {
            connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            try (Statement s = connection.createStatement()) {
                s.executeUpdate("CREATE TABLE IF NOT EXISTS accounts (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, password_hash TEXT NOT NULL, email TEXT NOT NULL UNIQUE, last_ip TEXT NOT NULL DEFAULT '', last_login_at INTEGER NOT NULL DEFAULT 0)");
                try { s.executeUpdate("ALTER TABLE accounts ADD COLUMN last_login_at INTEGER NOT NULL DEFAULT 0"); } catch (SQLException ignored) { }
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
            try (PreparedStatement p = connection.prepareStatement("INSERT INTO accounts(uuid,name,password_hash,email,last_ip,last_login_at) VALUES(?,?,?,?,?,0)")) {
                p.setString(1, uuid); p.setString(2, name); p.setString(3, BCrypt.hashpw(password, BCrypt.gensalt(12)));
                p.setString(4, email); p.setString(5, ip); p.executeUpdate();
            }
        }
        synchronized boolean passwordMatches(Account account, String password) {
            try { return BCrypt.checkpw(password, account.passwordHash()); } catch (IllegalArgumentException e) { return false; }
        }
        synchronized void updateLoginSession(String uuid, String ip, long timestamp) throws SQLException {
            try (PreparedStatement p = connection.prepareStatement("UPDATE accounts SET last_ip=?,last_login_at=? WHERE uuid=?")) {
                p.setString(1, ip); p.setLong(2, timestamp); p.setString(3, uuid); p.executeUpdate();
            }
        }
        @Override public void close() throws SQLException { connection.close(); }
    }
    private record Account(String name, String passwordHash, String email, String lastIp, long lastLoginAt) {}
}
