package dev.axo.login;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public final class AxoLogin extends JavaPlugin implements Listener, CommandExecutor {
    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private final Set<UUID> authenticated = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> attempts = new ConcurrentHashMap<>();
    private final Map<UUID, Long> deadlines = new ConcurrentHashMap<>();
    private AuthStore store;
    private int timeoutSeconds;
    private int maxAttempts;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        timeoutSeconds = Math.max(30, getConfig().getInt("timeout-seconds", 120));
        maxAttempts = Math.max(1, getConfig().getInt("max-attempts", 3));
        try {
            store = new AuthStore(getDataFolder().toPath().resolve("accounts.db"));
        } catch (Exception e) {
            getLogger().severe("Hesap veritabani acilamadi: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(this, this);
        for (String name : new String[]{"register", "login", "forgot", "reset"}) {
            if (getCommand(name) != null) getCommand(name).setExecutor(this);
        }
        getServer().getScheduler().runTaskTimer(this, this::checkTimeouts, 20L, 20L);
        getLogger().info("AxoLogin Paper eklentisi etkin. Bu eklentiyi Lobby Paper sunucusunun plugins klasorune kur.");
    }

    @Override
    public void onDisable() {
        if (store != null) {
            try { store.close(); } catch (Exception e) { getLogger().warning("Veritabani kapatilamadi: " + e.getMessage()); }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        authenticated.remove(player.getUniqueId());
        attempts.put(player.getUniqueId(), 0);
        deadlines.put(player.getUniqueId(), System.currentTimeMillis() + timeoutSeconds * 1000L);
        player.sendMessage(color("&8[&bAxoLogin&8] &eHos geldin! Devam etmek icin giris yap veya kayit ol."));
        player.sendMessage(color("&eKayit: &a/register <sifre> <tekrar> <email>"));
        player.sendMessage(color("&eGiris: &a/login <sifre>"));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        authenticated.remove(id);
        attempts.remove(id);
        deadlines.remove(id);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (isLocked(event.getPlayer()) && event.getTo() != null &&
                (event.getFrom().getBlockX() != event.getTo().getBlockX()
                || event.getFrom().getBlockY() != event.getTo().getBlockY()
                || event.getFrom().getBlockZ() != event.getTo().getBlockZ())) {
            event.setTo(event.getFrom());
        }
    }

    @EventHandler public void onBreak(BlockBreakEvent event) { if (isLocked(event.getPlayer())) event.setCancelled(true); }
    @EventHandler public void onPlace(BlockPlaceEvent event) { if (isLocked(event.getPlayer())) event.setCancelled(true); }
    @EventHandler public void onInteract(PlayerInteractEvent event) { if (isLocked(event.getPlayer())) event.setCancelled(true); }
    @EventHandler public void onDrop(PlayerDropItemEvent event) { if (isLocked(event.getPlayer())) event.setCancelled(true); }
    @EventHandler public void onInventory(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && isLocked(player)) event.setCancelled(true);
    }
    @EventHandler public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && isLocked(player)) event.setCancelled(true);
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        if (isLocked(event.getPlayer())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(color("&cGiris yapana kadar sohbet kullanilamaz. /register veya /login kullan."));
        }
    }

    @EventHandler
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        if (!isLocked(event.getPlayer())) return;
        String message = event.getMessage().toLowerCase(java.util.Locale.ROOT);
        String root = message.split("\\s+", 2)[0];
        if (!root.equals("/register") && !root.equals("/login") && !root.equals("/forgot") && !root.equals("/reset")) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(color("&cOnce /login veya /register kullanmalisin."));
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Bu komut oyuncular icindir.");
            return true;
        }
        String type = command.getName().toLowerCase(java.util.Locale.ROOT);
        if (type.equals("login") && authenticated.contains(player.getUniqueId())) {
            player.sendMessage(color("&aZaten giris yaptin."));
            return true;
        }
        try {
            var existing = store.get(player.getUniqueId().toString());
            if (type.equals("register")) {
                if (existing.isPresent()) { player.sendMessage(color("&eZaten kayitlisin. /login <sifre>")); return true; }
                if (args.length != 3 || !args[0].equals(args[1])) {
                    player.sendMessage(color("&eKullanim: /register <sifre> <tekrar> <email>")); return true;
                }
                if (args[0].length() < 8 || args[0].length() > 72) {
                    player.sendMessage(color("&cSifre 8-72 karakter olmali.")); return true;
                }
                if (!EMAIL.matcher(args[2]).matches()) {
                    player.sendMessage(color("&cGecerli bir e-posta adresi gir.")); return true;
                }
                if (store.emailTaken(args[2])) {
                    player.sendMessage(color("&cBu e-posta baska hesaba kayitli.")); return true;
                }
                store.create(player.getUniqueId().toString(), player.getName(), args[0], args[2], ipOf(player));
                completeLogin(player, "Kayit basarili! Hos geldin.");
                return true;
            }
            if (type.equals("login")) {
                if (existing.isEmpty()) {
                    player.sendMessage(color("&eOnce kayit ol: /register <sifre> <tekrar> <email>")); return true;
                }
                if (args.length != 1) { player.sendMessage(color("&eKullanim: /login <sifre>")); return true; }
                if (!store.passwordMatches(existing.get(), args[0])) {
                    int count = attempts.merge(player.getUniqueId(), 1, Integer::sum);
                    int left = maxAttempts - count;
                    player.sendMessage(color("&cSifre yanlis! Kalan deneme: " + Math.max(left, 0)));
                    if (left <= 0) player.kickPlayer(color("&cCok fazla hatali giris. Tekrar baglan."));
                    return true;
                }
                store.updateIp(player.getUniqueId().toString(), ipOf(player));
                completeLogin(player, "Giris basarili!");
                return true;
            }
            if (type.equals("forgot")) {
                player.sendMessage(color("&eE-posta ile sifre sifirlama henuz etkinlestirilmedi."));
                return true;
            }
            if (type.equals("reset")) {
                player.sendMessage(color("&eSifre sifirlama icin e-posta dogrulama sistemi henuz etkin degil."));
                return true;
            }
        } catch (Exception e) {
            getLogger().severe("Auth komutu hatasi: " + e.getMessage());
            player.sendMessage(color("&cBir hata olustu. Lutfen yoneticiye bildir."));
        }
        return true;
    }

    private void completeLogin(Player player, String message) {
        authenticated.add(player.getUniqueId());
        attempts.remove(player.getUniqueId());
        deadlines.remove(player.getUniqueId());
        player.sendMessage(color("&8[&bAxoLogin&8] &a✓ " + message));
        player.sendMessage(color("&aLobby'ye hos geldin!"));
    }

    private boolean isLocked(Player player) {
        return !authenticated.contains(player.getUniqueId());
    }

    private void checkTimeouts() {
        long now = System.currentTimeMillis();
        for (Player player : getServer().getOnlinePlayers()) {
            Long deadline = deadlines.get(player.getUniqueId());
            if (deadline != null && deadline < now && isLocked(player)) {
                player.kickPlayer(color("&cGiris suren doldu. Sunucuya tekrar baglan."));
            }
        }
    }

    private String ipOf(Player player) {
        if (player.getAddress() instanceof InetSocketAddress address && address.getAddress() != null) {
            return address.getAddress().getHostAddress();
        }
        return player.getAddress() == null ? "" : player.getAddress().getHostString();
    }

    private String color(String input) {
        return ChatColor.translateAlternateColorCodes('&', input);
    }
}
