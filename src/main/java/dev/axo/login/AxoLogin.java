package dev.axo.login;

import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Plugin(id = "axologin", name = "AxoLogin", version = "1.0.0", description = "Turkish authentication for Velocity", authors = {"Axo"})
public final class AxoLogin {
    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\\\.[A-Za-z]{2,}$");
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final Map<UUID, Long> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> attempts = new ConcurrentHashMap<>();
    private final Map<UUID, Long> deadlines = new ConcurrentHashMap<>();
    private AuthStore store;
    private String lobby = "lobby";
    private String authServer = "limbo";
    private int timeoutSeconds = 120;
    private int maxAttempts = 3;
    private long sessionMillis = Duration.ofHours(3).toMillis();

    @Inject
    public AxoLogin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy; this.logger = logger; this.dataDirectory = dataDirectory;
    }

    @Inject
    public void initialize() {
        try {
            java.nio.file.Files.createDirectories(dataDirectory);
            Path config = dataDirectory.resolve("config.yml");
            if (!java.nio.file.Files.exists(config)) {
                try (var in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
                    if (in != null) java.nio.file.Files.copy(in, config);
                }
            }
            store = new AuthStore(dataDirectory.resolve("accounts.db"));
            proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("register").plugin(this).build(), new AuthCommand("register"));
            proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("login").plugin(this).build(), new AuthCommand("login"));
            proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("forgot").plugin(this).build(), new AuthCommand("forgot"));
            proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("reset").plugin(this).build(), new AuthCommand("reset"));
            proxy.getScheduler().buildTask(this, this::checkTimeouts).repeat(1, 1, TimeUnit.SECONDS).schedule();
            logger.info("AxoLogin loaded. Configure an auth/limbo server before allowing players to join.");
        } catch (Exception e) {
            logger.error("AxoLogin could not start safely", e);
        }
    }

    @Subscribe public void onLogin(LoginEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        deadlines.put(id, System.currentTimeMillis() + timeoutSeconds * 1000L);
        attempts.put(id, 0);
    }

    @Subscribe public void onInitialServer(PlayerChooseInitialServerEvent event) {
        Player p = event.getPlayer();
        if (isAuthenticated(p)) return;
        proxy.getServer(authServer).ifPresentOrElse(event::setInitialServer, () ->
                logger.warn("Auth server '{}' is not registered in velocity.toml; configure it before testing AxoLogin.", authServer));
    }

    @Subscribe public void onPreConnect(ServerPreConnectEvent event) {
        Player p = event.getPlayer();
        if (isAuthenticated(p)) return;
        String target = event.getOriginalServer().getServerInfo().getName();
        if (!target.equalsIgnoreCase(authServer)) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            p.sendMessage(Component.text("Önce giriş yapmalısın! /login <şifre>", NamedTextColor.RED));
        }
    }

    @Subscribe public void onChat(PlayerChatEvent event) {
        Player p = event.getPlayer();
        if (!isAuthenticated(p) && !event.getMessage().startsWith("/")) {
            event.setResult(PlayerChatEvent.ChatResult.denied());
            p.sendMessage(Component.text("Bilgilerini sohbete yazma. /register veya /login komutlarını kullan.", NamedTextColor.YELLOW));
        }
    }

    @Subscribe public void onDisconnect(DisconnectEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        sessions.remove(id); attempts.remove(id); deadlines.remove(id);
    }

    private boolean isAuthenticated(Player p) {
        Long until = sessions.get(p.getUniqueId());
        if (until == null || until < System.currentTimeMillis()) { sessions.remove(p.getUniqueId()); return false; }
        try {
            var account = store.get(p.getUniqueId().toString());
            if (account.isEmpty()) return false;
            String ip = ipOf(p);
            return account.get().lastIp().equals(ip);
        } catch (Exception e) { logger.error("Could not check account session", e); return false; }
    }

    private String ipOf(Player p) {
        if (p.getRemoteAddress() instanceof InetSocketAddress a && a.getAddress() != null) return a.getAddress().getHostAddress();
        return p.getRemoteAddress().toString();
    }

    private void checkTimeouts() {
        long now = System.currentTimeMillis();
        for (Player p : proxy.getAllPlayers()) {
            Long deadline = deadlines.get(p.getUniqueId());
            if (deadline != null && deadline < now && !isAuthenticated(p)) {
                p.disconnect(Component.text("Giriş süren doldu. Sunucuya tekrar bağlan.", NamedTextColor.RED));
            }
        }
    }

    private final class AuthCommand implements SimpleCommand {
        private final String type;
        AuthCommand(String type) { this.type = type; }
        @Override public void execute(Invocation invocation) {
            if (!(invocation.source() instanceof Player p)) {
                invocation.source().sendMessage(Component.text("Bu komut oyuncular içindir.")); return;
            }
            String[] a = invocation.arguments();
            try {
                var existing = store.get(p.getUniqueId().toString());
                if (type.equals("register")) {
                    if (existing.isPresent()) { msg(p, "Zaten kayıtlısın. /login <şifre>", NamedTextColor.YELLOW); return; }
                    if (a.length != 3 || !a[0].equals(a[1])) { msg(p, "Kullanım: /register <şifre> <tekrar> <email>", NamedTextColor.YELLOW); return; }
                    if (a[0].length() < 8 || a[0].length() > 72) { msg(p, "Şifre 8-72 karakter olmalı.", NamedTextColor.RED); return; }
                    if (!EMAIL.matcher(a[2]).matches()) { msg(p, "Geçerli bir e-posta adresi gir.", NamedTextColor.RED); return; }
                    if (store.emailTaken(a[2])) { msg(p, "Bu e-posta başka hesaba kayıtlı.", NamedTextColor.RED); return; }
                    store.create(p.getUniqueId().toString(), p.getUsername(), a[0], a[2], ipOf(p));
                    authenticated(p); msg(p, "✓ Kayıt başarılı! Hoş geldin.", NamedTextColor.GREEN); return;
                }
                if (type.equals("login")) {
                    if (existing.isEmpty()) { msg(p, "Önce kayıt ol: /register <şifre> <tekrar> <email>", NamedTextColor.YELLOW); return; }
                    if (a.length != 1) { msg(p, "Kullanım: /login <şifre>", NamedTextColor.YELLOW); return; }
                    if (!store.passwordMatches(existing.get(), a[0])) {
                        int left = maxAttempts - attempts.merge(p.getUniqueId(), 1, Integer::sum);
                        msg(p, "Şifre yanlış! Kalan deneme: " + Math.max(left, 0), NamedTextColor.RED);
                        if (left <= 0) p.disconnect(Component.text("3 hatalı giriş denemesi. Tekrar bağlan.", NamedTextColor.RED));
                        return;
                    }
                    // A changed IP invalidates the old session; a correct password re-verifies the player.
                    authenticated(p); msg(p, "✓ Giriş başarılı!", NamedTextColor.GREEN); return;
                }
                if (type.equals("forgot")) {
                    msg(p, "E-posta doğrulamalı şifre sıfırlama SMTP ayarıyla bir sonraki aşamada etkinleştirilecek.", NamedTextColor.YELLOW); return;
                }
                if (type.equals("reset")) msg(p, "Önce e-posta doğrulama sistemi etkinleştirilmeli.", NamedTextColor.YELLOW);
            } catch (Exception e) { logger.error("Authentication command failed", e); msg(p, "Bir hata oluştu. Yöneticiye bildir.", NamedTextColor.RED); }
        }

        private void authenticated(Player p) {
            try { store.updateIp(p.getUniqueId().toString(), ipOf(p)); } catch (Exception e) { logger.error("Could not update IP", e); }
            sessions.put(p.getUniqueId(), System.currentTimeMillis() + sessionMillis);
            deadlines.remove(p.getUniqueId()); attempts.remove(p.getUniqueId());
            proxy.getServer(lobby).ifPresent(s -> p.createConnectionRequest(s).connect());
        }
    }

    private void msg(Player p, String text, NamedTextColor color) { p.sendMessage(Component.text("[AxoLogin] " + text, color)); }
}
