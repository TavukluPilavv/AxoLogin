package dev.axo.login;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Projectile;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
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
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.generator.ChunkGenerator;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AxoLogin extends JavaPlugin implements Listener, CommandExecutor {
    private final Set<UUID> authenticated = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> attempts = new ConcurrentHashMap<>();
    private final Map<UUID, Long> deadlines = new ConcurrentHashMap<>();
    private final Map<UUID, Long> sessionDeadlines = new ConcurrentHashMap<>();
    private static final long SESSION_MILLIS = 2L * 60L * 60L * 1000L;
    private final Map<UUID, BossBar> bars = new ConcurrentHashMap<>();
    private AuthStore store;
    private int timeoutSeconds;
    private int maxAttempts;
    private World voidWorld;
    private boolean musicEnabled;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        timeoutSeconds = Math.max(30, getConfig().getInt("timeout-seconds", 120));
        maxAttempts = Math.max(1, getConfig().getInt("max-attempts", 3));
        musicEnabled = getConfig().getBoolean("music-enabled", true);
        try {
            store = new AuthStore(getDataFolder().toPath().resolve("accounts.db"));
            createVoidWorld();
        } catch (Exception e) {
            getLogger().severe("AxoLogin baslatilamadi: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(this, this);
        for (String name : new String[]{"register", "login", "forgot", "axologin"}) {
            if (getCommand(name) != null) getCommand(name).setExecutor(this);
        }
        getServer().getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
        getLogger().info("AxoLogin Paper etkin. Lobby plugins klasorune kurulmalidir.");
    }

    @Override
    public void onDisable() {
        for (BossBar bar : bars.values()) bar.removeAll();
        bars.clear();
        if (store != null) {
            try { store.close(); } catch (Exception e) { getLogger().warning("Veritabani kapatilamadi: " + e.getMessage()); }
        }
    }

    private void createVoidWorld() {
        String name = getConfig().getString("void-world", "axologin_void");
        World existing = Bukkit.getWorld(name);
        if (existing != null) {
            voidWorld = existing;
            return;
        }
        ChunkGenerator emptyGenerator = new ChunkGenerator() {
            @Override
            public ChunkData generateChunkData(World world, Random random, int x, int z, BiomeGrid biome) {
                return createChunkData(world);
            }
        };
        voidWorld = new WorldCreator(name).generator(emptyGenerator).createWorld();
        if (voidWorld == null) throw new IllegalStateException("Giris icin bos dunya olusturulamadi.");
        voidWorld.setAutoSave(false);
        voidWorld.setGameRule(org.bukkit.GameRule.DO_DAYLIGHT_CYCLE, false);
        voidWorld.setGameRule(org.bukkit.GameRule.DO_WEATHER_CYCLE, false);
        voidWorld.setTime(18000L);
        voidWorld.setStorm(false);
        voidWorld.setThundering(false);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        event.joinMessage(null);
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        authenticated.remove(id);
        attempts.put(id, 0);
        try {
            var account = store.get(id.toString());
            long now = System.currentTimeMillis();
            String currentIp = ipOf(player);
            if (account.isPresent() && !currentIp.isBlank()
                    && currentIp.equals(account.get().lastIp())
                    && account.get().lastLoginAt() > 0
                    && now - account.get().lastLoginAt() < SESSION_MILLIS) {
                sessionDeadlines.put(id, account.get().lastLoginAt() + SESSION_MILLIS);
                completeLogin(player, "Güvenli oturumunuz hatırlandı.", false);
                return;
            }
        } catch (Exception e) {
            getLogger().warning("Oturum kontrolü yapılamadı: " + e.getMessage());
        }
        sessionDeadlines.remove(id);
        deadlines.put(id, System.currentTimeMillis() + timeoutSeconds * 1000L);
        player.setGameMode(GameMode.ADVENTURE);
        player.setInvulnerable(true);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setCollidable(false);
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.equals(player)) {
                online.hidePlayer(this, player);
                player.hidePlayer(this, online);
            }
        }
        Location start = new Location(voidWorld,
                getConfig().getDouble("void-x", 0.5),
                getConfig().getDouble("void-y", 100.0),
                getConfig().getDouble("void-z", 0.5),
                (float) getConfig().getDouble("void-yaw", 0.0),
                (float) getConfig().getDouble("void-pitch", 0.0));
        player.teleport(start);
        BossBar bar = Bukkit.createBossBar("§bAxoLogin §8• §fLütfen giriş yapınız §7(" + timeoutSeconds + "s)", BarColor.BLUE, BarStyle.SOLID);
        bar.setProgress(1.0);
        bar.addPlayer(player);
        bars.put(id, bar);
        player.sendTitle("§b§lAxoLogin", "§fLütfen giriş yapınız veya kayıt olunuz", 10, 70, 10);
        player.sendMessage("§8§m--------------------------------");
        player.sendMessage("§b§lAxoLogin §8» §fHoş geldin, §b" + player.getName());
        player.sendMessage("§fHesabın varsa §a/login <şifre> §fkomutunu kullan.");
        player.sendMessage("§fHesabın yoksa §a/register <şifre> §fkomutuyla kayıt ol.");
        player.sendMessage("§7Giriş süren: §e" + timeoutSeconds + " saniye");
        player.sendMessage("§8§m--------------------------------");
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.2f);
        if (musicEnabled) {
            player.playSound(player.getLocation(), Sound.MUSIC_DISC_OTHERSIDE, 0.22f, 1.0f);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        event.quitMessage(null);
        UUID id = event.getPlayer().getUniqueId();
        authenticated.remove(id);
        attempts.remove(id);
        deadlines.remove(id);
        BossBar bar = bars.remove(id);
        if (bar != null) bar.removeAll();
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (isLocked(event.getPlayer()) && event.getTo() != null &&
                (event.getFrom().getBlockX() != event.getTo().getBlockX()
                || event.getFrom().getBlockY() != event.getTo().getBlockY()
                || event.getFrom().getBlockZ() != event.getTo().getBlockZ())) {
            Location from = event.getFrom();
            event.setTo(new Location(from.getWorld(), from.getX(), from.getY(), from.getZ(), event.getTo().getYaw(), event.getTo().getPitch()));
            return;
        }
        Player p = event.getPlayer();
        if (isLobby(p.getWorld()) && !p.isOp() && p.isFlying()) p.setFlying(false);
    }

    @EventHandler public void onBreak(BlockBreakEvent event) { if (isLocked(event.getPlayer())) { event.setCancelled(true); loginRequired(event.getPlayer()); return; }
        if (isLobby(event.getPlayer().getWorld()) && !event.getPlayer().isOp()) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(Component.text("§c✖ §fLobi dünyasında blok kıramazsınız."));
        }
    }
    @EventHandler public void onPlace(BlockPlaceEvent event) {
        Player p = event.getPlayer();
        if (isLocked(p)) { event.setCancelled(true); loginRequired(p); return; }
        if (isLobby(p.getWorld()) && !p.isOp()) {
            event.setCancelled(true);
            p.sendActionBar(Component.text(event.getBlockPlaced().getType() == Material.TNT ? "§c✖ §fLobide TNT kullanamazsınız." : "§c✖ §fLobi dünyasında blok koyamazsınız."));
        }
    }
    @EventHandler public void onInteract(PlayerInteractEvent event) {
        Player p = event.getPlayer();
        if (isLocked(p)) { event.setCancelled(true); loginRequired(p); return; }
        if (isLobby(p.getWorld()) && !p.isOp() && event.getItem() != null) {
            Material m = event.getItem().getType();
            if (m == Material.ENDER_PEARL || m == Material.ENDER_EYE) {
                event.setCancelled(true);
                p.sendActionBar(Component.text("§c✖ §fLobide ender incisi kullanamazsınız."));
            } else if (m == Material.FLINT_AND_STEEL || m == Material.FIRE_CHARGE) {
                event.setCancelled(true);
                p.sendActionBar(Component.text("§c✖ §fLobide ateş yakamazsınız."));
            }
        }
    }
    @EventHandler public void onDrop(PlayerDropItemEvent event) {
        if (isLocked(event.getPlayer())) { event.setCancelled(true); loginRequired(event.getPlayer()); }
    }
    @EventHandler public void onSwap(PlayerSwapHandItemsEvent event) {
        if (isLocked(event.getPlayer())) { event.setCancelled(true); loginRequired(event.getPlayer()); }
    }
    @EventHandler public void onInventory(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && isLocked(player)) { event.setCancelled(true); loginRequired(player); }
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPvp(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = null;
        if (event.getDamager() instanceof Player directPlayer) attacker = directPlayer;
        else if (event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) attacker = shooter;
        if (attacker != null && isLocked(attacker)) { event.setCancelled(true); loginRequired(attacker); return; }
        if (isLobby(victim.getWorld())) {
            event.setCancelled(true);
            if (attacker != null) attacker.sendActionBar(Component.text("§c✖ §fLobide PvP kapalı."));
        }
    }
    @EventHandler public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            if (isLocked(player)) event.setCancelled(true);
            else if (isLobby(player.getWorld()) && !player.isOp()) event.setCancelled(true);
        }
    }
    @EventHandler public void onFlight(PlayerToggleFlightEvent event) {
        Player p = event.getPlayer();
        if (isLocked(p)) { event.setCancelled(true); loginRequired(p); }
        else if (isLobby(p.getWorld()) && !p.isOp() && event.isFlying()) {
            event.setCancelled(true); p.setAllowFlight(false);
            p.sendActionBar(Component.text("§c✖ §fLobide uçuş kapalı."));
        }
    }
    @EventHandler public void onIgnite(BlockIgniteEvent event) {
        if (isLobby(event.getBlock().getWorld()) && event.getPlayer() != null && !event.getPlayer().isOp()) event.setCancelled(true);
    }
    @EventHandler public void onTntExplode(EntityExplodeEvent event) {
        if (isLobby(event.getLocation().getWorld()) && event.getEntity() instanceof TNTPrimed) event.setCancelled(true);
    }
    @EventHandler public void onBlockExplode(BlockExplodeEvent event) {
        if (isLobby(event.getBlock().getWorld())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncPlayerChatEvent event) {
        if (isLocked(event.getPlayer())) {
            event.setCancelled(true);
            Bukkit.getScheduler().runTask(this, () -> loginRequired(event.getPlayer()));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        if (!isLocked(event.getPlayer())) return;
        String root = event.getMessage().toLowerCase(java.util.Locale.ROOT).split("\\s+", 2)[0];
        if (!root.equals("/register") && !root.equals("/login") && !root.equals("/forgot")) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(Component.text("§c✖ §fÖnce lütfen giriş yapınız."));
            event.getPlayer().playSound(event.getPlayer().getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.7f);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String type = command.getName().toLowerCase(java.util.Locale.ROOT);
        if (type.equals("axologin")) {
            if (!sender.hasPermission("axologin.admin")) {
                sender.sendMessage("§cBu komutu kullanma yetkiniz yok.");
                return true;
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("reset")) {
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) { sender.sendMessage("§cOyuncu çevrimiçi olmalıdır."); return true; }
                if (args[2].length() < 8 || args[2].length() > 72) { sender.sendMessage("§cYeni şifre 8-72 karakter olmalıdır."); return true; }
                try {
                    store.updatePassword(target.getUniqueId().toString(), args[2]);
                    target.sendMessage("§eŞifreniz yetkili tarafından sıfırlandı. Yeni şifrenizle giriş yapınız.");
                    sender.sendMessage("§a" + target.getName() + " oyuncusunun şifresi sıfırlandı.");
                } catch (Exception e) { sender.sendMessage("§cŞifre sıfırlanamadı."); getLogger().warning(e.getMessage()); }
                return true;
            }
            sender.sendMessage("§eKullanım: /axologin reset <oyuncu> <yeni-şifre>");
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Bu komut oyuncular içindir.");
            return true;
        }
        UUID id = player.getUniqueId();
        if (type.equals("login") && authenticated.contains(id)) {
            player.sendActionBar(Component.text("§a✔ §fZaten giriş yaptınız."));
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.4f);
            return true;
        }
        try {
            var existing = store.get(id.toString());
            if (type.equals("register")) {
                if (existing.isPresent()) { fail(player, "Hesabınız zaten kayıtlı. Lütfen /login <şifre> kullanınız."); return true; }
                if (args.length != 3) { fail(player, "Kayıt için: /register <şifre> <şifre-tekrar> <Gmail>"); return true; }
                if (args[0].length() < 8 || args[0].length() > 72) { fail(player, "Şifre 8-72 karakter arasında olmalıdır."); return true; }
                if (!args[0].equals(args[1])) { fail(player, "Şifreler aynı değil. İki şifreyi de kontrol ediniz."); return true; }
                if (!args[2].matches("(?i)^[A-Z0-9._%+-]+@gmail\\.com$")) { fail(player, "Lütfen geçerli bir @gmail.com adresi kullanınız."); return true; }
                store.create(id.toString(), player.getName(), args[0], args[2].toLowerCase(java.util.Locale.ROOT), ipOf(player));
                completeLogin(player, "Kayıt başarılı! Hoş geldiniz.");
                return true;
            }
            if (type.equals("login")) {
                if (existing.isEmpty()) { fail(player, "Hesabınız yok. Lütfen kayıt olunuz: /register <şifre>"); return true; }
                if (args.length != 1) { fail(player, "Giriş yapmak için: /login <şifre>"); return true; }
                if (!store.passwordMatches(existing.get(), args[0])) {
                    int count = attempts.merge(id, 1, Integer::sum);
                    int left = maxAttempts - count;
                    fail(player, "Şifre yanlış! Kalan deneme: " + Math.max(left, 0));
                    if (left <= 0) player.kickPlayer("§cÇok fazla hatalı giriş yaptınız. Lütfen tekrar bağlanınız.");
                    return true;
                }
                completeLogin(player, "Giriş başarılı! Hoş geldiniz.");
                return true;
            }
            if (type.equals("forgot")) {
                player.sendMessage("§bAxoLogin §8» §fŞifrenizi sıfırlamak için yetkili ekiple iletişime geçiniz.");
                player.sendMessage("§7Yetkili: §e/axologin reset <oyuncu> <yeni-şifre>");
                return true;
            }
        } catch (Exception e) {
            getLogger().severe("Kimlik doğrulama komutu hatası: " + e.getMessage());
            fail(player, "Bir hata oluştu. Lütfen yetkiliye bildiriniz.");
        }
        return true;
    }

    private void completeLogin(Player player, String message) {
        completeLogin(player, message, true);
    }

    private void completeLogin(Player player, String message, boolean refreshSession) {
        UUID id = player.getUniqueId();
        authenticated.add(id);
        attempts.remove(id);
        deadlines.remove(id);
        if (refreshSession) {
            long expiry = System.currentTimeMillis() + SESSION_MILLIS;
            sessionDeadlines.put(id, expiry);
            try { store.updateLoginSession(id.toString(), ipOf(player), System.currentTimeMillis()); }
            catch (Exception e) { getLogger().warning("Oturum kaydedilemedi: " + e.getMessage()); }
        }
        BossBar bar = bars.remove(id);
        if (bar != null) bar.removeAll();
        player.setInvulnerable(false);
        player.setFlying(false);
        player.setAllowFlight(false);
        player.setCollidable(true);
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.showPlayer(this, player);
            player.showPlayer(this, online);
        }
        World lobby = Bukkit.getWorld(getConfig().getString("lobby-world", "world"));
        if (lobby == null && !Bukkit.getWorlds().isEmpty()) lobby = Bukkit.getWorlds().get(0);
        if (lobby != null) {
            Location spawn = lobby.getSpawnLocation();
            player.teleport(spawn);
        }
        player.sendActionBar(Component.text("§a✔ §f" + message));
        player.sendTitle("§a✔", "§f" + message, 0, 35, 10);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.0f);
        player.sendMessage("§8[§bAxoLogin§8] §a" + message);
    }

    private void fail(Player player, String message) {
        player.sendActionBar(Component.text("§c✖ §f" + message));
        player.sendTitle("§c✖", "§f" + message, 0, 35, 10);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.7f);
        player.sendMessage("§c✖ §f" + message);
    }

    private void loginRequired(Player player) {
        if (!isLocked(player)) return;
        player.sendActionBar(Component.text("§c✖ §fLütfen önce giriş yapınız!"));
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.55f, 0.8f);
        player.sendMessage("§cAxoLogin §8» §fLütfen önce /login veya /register komutuyla giriş yapınız.");
    }

    private boolean isLobby(World world) {
        return world != null && world.getName().equalsIgnoreCase(getConfig().getString("lobby-world", "world"));
    }

    private boolean isLocked(Player player) {
        return !authenticated.contains(player.getUniqueId());
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            if (isLocked(player)) {
                Long deadline = deadlines.get(id);
                if (deadline != null) {
                    long remaining = Math.max(0, (deadline - now + 999) / 1000);
                    BossBar bar = bars.get(id);
                    if (bar != null) {
                        bar.setTitle("§bAxoLogin §8• §fLütfen giriş yapınız §7(" + remaining + "s)");
                        bar.setProgress(Math.max(0.001, Math.min(1.0, (double) remaining / timeoutSeconds)));
                    }
                    if (deadline <= now) {
                        player.kickPlayer("§cGiriş süreniz doldu. Lütfen tekrar bağlanınız.");
                    }
                }
            }
        }
    }

    private String ipOf(Player player) {
        if (player.getAddress() instanceof InetSocketAddress address && address.getAddress() != null) {
            return address.getAddress().getHostAddress();
        }
        return player.getAddress() == null ? "" : player.getAddress().getHostString();
    }
}
