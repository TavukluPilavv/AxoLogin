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
import org.bukkit.block.Biome;

import java.net.InetSocketAddress;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public final class AxoLogin extends JavaPlugin implements Listener, CommandExecutor {
    private final Set<UUID> authenticated = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> attempts = new ConcurrentHashMap<>();
    private final Map<UUID, Long> deadlines = new ConcurrentHashMap<>();
    private final Map<UUID, Long> sessionDeadlines = new ConcurrentHashMap<>();
    private static final long SESSION_MILLIS = 3L * 60L * 60L * 1000L;
    private static final ZoneId SESSION_ZONE = ZoneId.of("Europe/Istanbul");
    private final Map<UUID, Location> loginSpawns = new ConcurrentHashMap<>();
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
        } catch (Exception e) {
            getLogger().severe("AxoLogin baslatilamadi: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
        getServer().getPluginManager().registerEvents(this, this);
        for (String name : new String[]{"register", "login", "forgot", "axologin"}) {
            if (getCommand(name) != null) getCommand(name).setExecutor(this);
        }
        getServer().getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
        getLogger().info("AxoLogin Paper etkin. Giris basarili olunca Velocity uzerinden lobby sunucusuna aktarir.");
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, "BungeeCord");
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
        boolean registered = false;
        try {
            var account = store.get(id.toString());
            registered = account.isPresent();
            long now = System.currentTimeMillis();
            String currentIp = ipOf(player);
            if (account.isPresent() && !currentIp.isBlank()
                    && currentIp.equals(account.get().lastIp())
                    && account.get().lastLoginAt() > 0
                    && now - account.get().lastLoginAt() < SESSION_MILLIS
                    && LocalDate.ofInstant(Instant.ofEpochMilli(now), SESSION_ZONE)
                        .equals(LocalDate.ofInstant(Instant.ofEpochMilli(account.get().lastLoginAt()), SESSION_ZONE))) {
                sessionDeadlines.put(id, sessionExpiry(account.get().lastLoginAt()));
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
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setCollidable(false);
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.equals(player)) {
                online.hidePlayer(this, player);
                player.hidePlayer(this, online);
            }
        }
        // Spawn at the configured world spawn, never in a separate void world.
        Location safeSpawn = findUniqueLoginSpawn(player);
        loginSpawns.put(id, safeSpawn);
        player.teleport(safeSpawn);
        BossBar bar = Bukkit.createBossBar("§bAxoLogin §8• §fLütfen giriş yapınız §7(" + timeoutSeconds + "s)", BarColor.BLUE, BarStyle.SOLID);
        bar.setProgress(1.0);
        bar.addPlayer(player);
        bars.put(id, bar);
        player.sendTitle("§b§lAxoLogin", "§fLütfen giriş yapınız veya kayıt olunuz", 10, 70, 10);
        player.sendMessage("§8§m--------------------------------");
        player.sendMessage("§b§lAxoLogin §8» §fHoş geldin, §b" + player.getName());
        if (registered) {
            player.sendMessage("§fHesabınız kayıtlı. Giriş yapmak için §a/login <şifre> §fkomutunu kullanın.");
        } else {
            player.sendMessage("§fHesabınız kayıtlı değil. Kayıt olmak için §a/register <şifre> <şifre-tekrar> <Gmail> §fkomutunu kullanın.");
        }
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
        sessionDeadlines.remove(id);
        loginSpawns.remove(id);
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
        if (isLoginWorld(event.getPlayer().getWorld()) && !event.getPlayer().isOp()) { event.setCancelled(true); return; }
        if (isLobby(event.getPlayer().getWorld()) && !event.getPlayer().isOp()) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(Component.text("§c✖ §fLobi dünyasında blok kıramazsınız."));
        }
    }
    @EventHandler public void onPlace(BlockPlaceEvent event) {
        Player p = event.getPlayer();
        if (isLocked(p)) { event.setCancelled(true); loginRequired(p); return; }
        if (isLoginWorld(p.getWorld()) && !p.isOp()) { event.setCancelled(true); return; }
        if (isLobby(p.getWorld()) && !p.isOp()) {
            event.setCancelled(true);
            p.sendActionBar(Component.text(event.getBlockPlaced().getType() == Material.TNT ? "§c✖ §fLobide TNT kullanamazsınız." : "§c✖ §fLobi dünyasında blok koyamazsınız."));
        }
    }
    @EventHandler public void onInteract(PlayerInteractEvent event) {
        Player p = event.getPlayer();
        if (isLocked(p)) { event.setCancelled(true); loginRequired(p); return; }
        if (isLoginWorld(p.getWorld()) && !p.isOp() && event.getItem() != null) {
            Material type = event.getItem().getType();
            if (type == Material.TNT || type == Material.FLINT_AND_STEEL || type == Material.FIRE_CHARGE) { event.setCancelled(true); return; }
        }
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
        if (isLoginWorld(victim.getWorld()) && ((attacker != null && !attacker.isOp()) || !victim.isOp())) { event.setCancelled(true); return; }
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
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFlight(PlayerToggleFlightEvent event) {
        Player p = event.getPlayer();
        // Login voidunda uçuş gereklidir; lobby uçuş kısıtlaması kilitli oyunculara uygulanmaz.
        if (isLocked(p)) {
            event.setCancelled(true);
            p.setFlying(false);
            p.setAllowFlight(false);
            return;
        }
        if (isLobby(p.getWorld()) && !p.isOp() && event.isFlying()) {
            event.setCancelled(true);
            p.setFlying(false);
            p.setAllowFlight(false);
            p.sendActionBar(Component.text("§c✖ §fLobide uçuş kapalı."));
        }
    }
    @EventHandler public void onIgnite(BlockIgniteEvent event) {
        if (event.getPlayer() != null && !event.getPlayer().isOp()
                && (isLoginWorld(event.getBlock().getWorld()) || isLobby(event.getBlock().getWorld()))) event.setCancelled(true);
    }
    @EventHandler public void onTntExplode(EntityExplodeEvent event) {
        if ((isLoginWorld(event.getLocation().getWorld()) || isLobby(event.getLocation().getWorld()))
                && event.getEntity() instanceof TNTPrimed) event.setCancelled(true);
    }
    @EventHandler public void onBlockExplode(BlockExplodeEvent event) {
        if (isLoginWorld(event.getBlock().getWorld()) || isLobby(event.getBlock().getWorld())) event.setCancelled(true);
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
        Player player = event.getPlayer();
        if (!isLocked(player)) return;
        String root = event.getMessage().toLowerCase(java.util.Locale.ROOT).split("\\s+", 2)[0];
        if (!root.equals("/register") && !root.equals("/login") && !root.equals("/forgot")) {
            event.setCancelled(true);
            try {
                if (store.get(player.getUniqueId().toString()).isPresent()) {
                    player.sendMessage("§cAxoLogin §8» §fHesabınız kayıtlı. Lütfen /login <şifre> kullanınız.");
                    player.sendActionBar(Component.text("§c✖ §fÖnce /login <şifre> kullanınız."));
                } else {
                    player.sendMessage("§cAxoLogin §8» §fHenüz kayıtlı değilsiniz. Lütfen /register <şifre> <şifre-tekrar> <Gmail> kullanınız.");
                    player.sendActionBar(Component.text("§c✖ §fÖnce kayıt olunuz."));
                }
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.55f, 0.8f);
            } catch (Exception e) { loginRequired(player); }
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
                if (existing.isEmpty()) { fail(player, "Hesabınız kayıtlı değil. Lütfen /register <şifre> <şifre-tekrar> <Gmail> kullanınız."); return true; }
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
            long loginAt = System.currentTimeMillis();
            sessionDeadlines.put(id, sessionExpiry(loginAt));
            try { store.updateLoginSession(id.toString(), ipOf(player), loginAt);
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
        String welcome = "Hoş geldin, " + player.getName();
        player.sendActionBar(Component.text("§a✔ §f" + welcome));
        player.sendTitle("§a✔", "§f" + welcome, 0, 40, 10);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.6f);
        player.sendMessage("§8[§bAxoLogin§8] §a" + message);
        try {
            List<String> sameIpNames = store.namesByIp(ipOf(player));
            player.sendMessage("§bBu IP ile oluşturulan hesap sayısı: §e" + sameIpNames.size());
            player.sendMessage("§bBu IP'deki hesaplar: §f" + (sameIpNames.isEmpty() ? "Yok" : String.join(", ", sameIpNames)));
        } catch (Exception e) { getLogger().warning("IP hesap listesi okunamadi: " + e.getMessage()); }
        // Transfer the authenticated player through Velocity to the configured backend.
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline()) return;
            try {
                ByteArrayDataOutput out = ByteStreams.newDataOutput();
                out.writeUTF("Connect");
                out.writeUTF(getConfig().getString("lobby-server", "lobby"));
                player.sendPluginMessage(this, "BungeeCord", out.toByteArray());
            } catch (Exception e) {
                getLogger().warning("Oyuncu Lobby sunucusuna aktarilamadi: " + e.getMessage());
                player.sendMessage("§cLobby sunucusuna geçiş yapılamadı. Lütfen yetkiliye bildirin.");
            }
        }, 10L);
    }

    private void fail(Player player, String message) {
        player.sendActionBar(Component.text("§c✖ §f" + message));
        player.sendTitle("§c✖", "§f" + message, 0, 35, 10);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.7f);
        player.sendMessage("§c✖ §f" + message);
    }

    private void loginRequired(Player player) {
        if (!isLocked(player)) return;
        try {
            if (store.get(player.getUniqueId().toString()).isPresent()) {
                player.sendActionBar(Component.text("§c✖ §fHesabınız kayıtlı: /login <şifre>"));
                player.sendMessage("§cAxoLogin §8» §fHesabınız kayıtlı. Lütfen /login <şifre> kullanınız.");
            } else {
                player.sendActionBar(Component.text("§c✖ §fKayıt olun: /register <şifre> <şifre-tekrar> <Gmail>"));
                player.sendMessage("§cAxoLogin §8» §fKayıtlı değilsiniz. Lütfen /register <şifre> <şifre-tekrar> <Gmail> kullanınız.");
            }
        } catch (Exception e) {
            player.sendActionBar(Component.text("§c✖ §fÖnce giriş yapınız."));
        }
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.55f, 0.8f);
    }

    private boolean isLoginWorld(World world) {
        return world != null && world.getName().equalsIgnoreCase(getConfig().getString("login-world", "world"));
    }

    private Location findUniqueLoginSpawn(Player player) {
        World world = player.getWorld();
        Location origin = world.getSpawnLocation();
        for (int slot = 0; slot < 10000; slot++) {
            int column = slot % 9;
            int row = slot / 9;
            int x = origin.getBlockX() + (column - 4) * 8;
            int z = origin.getBlockZ() + (row + 1) * 8;
            int y = world.getHighestBlockYAt(x, z);
            Location candidate = new Location(world, x + 0.5, y + 1.0, z + 0.5, origin.getYaw(), origin.getPitch());
            if (!world.getBlockAt(x, y, z).getType().isSolid()
                    || !world.getBlockAt(x, y + 1, z).isPassable()
                    || !world.getBlockAt(x, y + 2, z).isPassable()) continue;
            boolean occupied = loginSpawns.values().stream().anyMatch(loc -> loc.getWorld() != null
                    && loc.getWorld().equals(world) && loc.distanceSquared(candidate) < 36.0);
            if (!occupied) return candidate;
        }
        throw new IllegalStateException("Oyuncu icin bos giris noktasi bulunamadi.");
    }

    private long sessionExpiry(long loginAt) {
        long threeHours = loginAt + SESSION_MILLIS;
        LocalDate nextDay = LocalDate.ofInstant(Instant.ofEpochMilli(loginAt), SESSION_ZONE).plusDays(1);
        long dayEnd = nextDay.atStartOfDay(SESSION_ZONE).toInstant().toEpochMilli();
        return Math.min(threeHours, dayEnd);
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
            Long sessionEnd = sessionDeadlines.get(id);
            if (authenticated.contains(id) && sessionEnd != null && sessionEnd <= now) {
                requireReauthentication(player);
                continue;
            }
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

    private void requireReauthentication(Player player) {
        UUID id = player.getUniqueId();
        authenticated.remove(id);
        sessionDeadlines.remove(id);
        deadlines.put(id, System.currentTimeMillis() + timeoutSeconds * 1000L);
        player.setGameMode(GameMode.ADVENTURE);
        player.setInvulnerable(true);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setCollidable(false);
        // Keep the player at their current location when re-authentication is required.
        BossBar oldBar = bars.remove(id);
        if (oldBar != null) oldBar.removeAll();
        BossBar bar = Bukkit.createBossBar("§bAxoLogin §8• §fOturum süreniz doldu §7(" + timeoutSeconds + "s)", BarColor.BLUE, BarStyle.SOLID);
        bar.setProgress(1.0);
        bar.addPlayer(player);
        bars.put(id, bar);
        player.sendTitle("§c✖", "§fOturum süreniz doldu, tekrar giriş yapınız", 10, 60, 10);
        player.sendMessage("§cAxoLogin §8» §f3 saatlik veya günlük oturum süreniz doldu. /login <şifre> kullanınız.");
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.equals(player)) {
                online.hidePlayer(this, player);
                player.hidePlayer(this, online);
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
