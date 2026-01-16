package com.homeofcappuccino.purgatory;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Plugin: PurgatoryRules
 * Purpose: Manages purgatory rules, punishments, bossbar countdown, and world reset.
 * Notes: Built for Paper 1.21.x (Java 21).
 */

public class PurgatoryRulesPlugin extends JavaPlugin implements Listener {
    // Compile with Java 21 target (class file version 65) for Paper 1.21.x.
    private String purgatoryWorld;
    private Location portalTarget;
    private Location punishTarget;
    private Set<String> punishWorlds = new HashSet<>();
    private boolean resetDeathStat;
    private boolean enablePortalRedirect;
    private long punishCooldownMs;
    private String backupCommand;
    private boolean clearInventory;
    private boolean clearXp;
    private long releaseAfterMs;
    private String releaseMessage;
    private TitleConfig titleConfig;
    private BossBarConfig bossBarConfig;
    private boolean resetEnabled;
    private String resetTemplatePath;
    private boolean resetOnEmpty;

    private final Map<UUID, PunishData> punishments = Collections.synchronizedMap(new HashMap<>());
    private final Map<String, SinConfig> sins = new HashMap<>();
    private final Map<UUID, Map<String, HitState>> sinHits = Collections.synchronizedMap(new HashMap<>());
    private final Map<UUID, Map<String, Long>> sinPunishMs = Collections.synchronizedMap(new HashMap<>());

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        punishments.clear();
        sins.clear();
        sinHits.clear();
        sinPunishMs.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("purgatoryrules")) {
            return false;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            loadConfigValues();
            sender.sendMessage(ChatColor.GREEN + "[PurgatoryRules] Config reloaded.");
            return true;
        }
        sender.sendMessage(ChatColor.YELLOW + "[PurgatoryRules] Usage: /purgatoryrules reload");
        return true;
    }

    private void loadConfigValues() {
        purgatoryWorld = getConfig().getString("purgatory.world", "purgatory");
        enablePortalRedirect = getConfig().getBoolean("purgatory.portal-redirect.enabled", true);
        resetDeathStat = getConfig().getBoolean("purgatory.reset-death-stat", true);

        portalTarget = loadLocation("purgatory.portal-redirect.target", purgatoryWorld, -30, 126, 119, 0f, 0f);
        punishTarget = loadLocation(resolveConfigPath("purgatory.punish.target", "purgatory.pvp-punish.target"),
                purgatoryWorld, 53, 91, 127, 0f, 0f);

        punishWorlds.clear();
        for (String worldName : getConfig().getStringList(resolveConfigPath("purgatory.punish.worlds",
                "purgatory.pvp-punish.worlds"))) {
            punishWorlds.add(worldName);
        }

        punishCooldownMs = Math.max(0L, getConfig().getLong(resolveConfigPath("purgatory.punish.cooldown-ms",
                "purgatory.pvp-punish.cooldown-ms"), 2000L));
        backupCommand = getConfig().getString(resolveConfigPath("purgatory.punish.backup-command",
                "purgatory.pvp-punish.backup-command"), "irp forcebackup player {player}");
        clearInventory = getConfig().getBoolean(resolveConfigPath("purgatory.punish.clear-inventory",
                "purgatory.pvp-punish.clear-inventory"), true);
        clearXp = getConfig().getBoolean(resolveConfigPath("purgatory.punish.clear-xp",
                "purgatory.pvp-punish.clear-xp"), true);
        releaseAfterMs = Math.max(0L, getConfig().getLong(resolveConfigPath("purgatory.punish.release-after-seconds",
                "purgatory.pvp-punish.release-after-seconds"), 120L)) * 1000L;
        releaseMessage = colorize(getConfig().getString(resolveConfigPath("purgatory.punish.release-message",
                "purgatory.pvp-punish.release-message"), ""));
        titleConfig = TitleConfig.fromConfig(getConfig().getConfigurationSection(resolveConfigPath(
                "purgatory.punish.title", "purgatory.pvp-punish.title")));
        bossBarConfig = BossBarConfig.fromConfig(getConfig().getConfigurationSection(resolveConfigPath(
                "purgatory.punish.bossbar", "purgatory.pvp-punish.bossbar")));

        resetEnabled = getConfig().getBoolean("purgatory.reset.enabled", false);
        resetTemplatePath = getConfig().getString("purgatory.reset.template-path", "");
        resetOnEmpty = getConfig().getBoolean("purgatory.reset.restore-on-empty", true);

        sinHits.clear();
        sinPunishMs.clear();
        loadSins();
    }

    private String resolveConfigPath(String preferred, String fallback) {
        if (getConfig().isConfigurationSection(preferred) || getConfig().contains(preferred)) {
            return preferred;
        }
        return fallback;
    }

    private void loadSins() {
        sins.clear();
        ConfigurationSection section = getConfig().getConfigurationSection("purgatory.sins");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection sinSection = section.getConfigurationSection(key);
            if (sinSection == null) continue;
            SinConfig sin = SinConfig.fromConfig(key, sinSection);
            sins.put(sin.id, sin);
        }
    }

    private Location loadLocation(String path, String worldName, int x, int y, int z, float yaw, float pitch) {
        ConfigurationSection section = getConfig().getConfigurationSection(path);
        if (section == null) {
            return new Location(getWorld(worldName), x, y, z, yaw, pitch);
        }
        int cx = section.getInt("x", x);
        int cy = section.getInt("y", y);
        int cz = section.getInt("z", z);
        float cyaw = (float) section.getDouble("yaw", yaw);
        float cpitch = (float) section.getDouble("pitch", pitch);
        return new Location(getWorld(worldName), cx, cy, cz, cyaw, cpitch);
    }

    private World getWorld(String name) {
        World world = Bukkit.getWorld(name);
        if (world == null) {
            getLogger().warning("World not loaded: " + name);
        }
        return world;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (sins.isEmpty()) return;
        Player attacker = resolveAttacker(event);
        if (attacker == null) return;
        Entity victim = event.getEntity();
        if (victim instanceof Player && victim.getUniqueId().equals(attacker.getUniqueId())) {
            return;
        }
        boolean critical = isCriticalHit(attacker);

        for (SinConfig sin : sins.values()) {
            if (!sin.enabled) continue;
            if (!sin.appliesToWorld(attacker.getWorld().getName(), punishWorlds)) continue;
            if (!sin.matches(event, victim)) continue;
            handleSin(attacker, victim, sin, critical);
        }
    }

    private Player resolveAttacker(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player damager) {
            return damager;
        }
        if (event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }

    private boolean shouldPunish(UUID uuid, String sinId, long cooldownMs) {
        long now = System.currentTimeMillis();
        Map<String, Long> perSin = sinPunishMs.computeIfAbsent(uuid, key -> Collections.synchronizedMap(new HashMap<>()));
        Long last = perSin.get(sinId);
        if (last != null && now - last < cooldownMs) {
            return false;
        }
        perSin.put(sinId, now);
        return true;
    }

    private boolean isCriticalHit(Player attacker) {
        return !attacker.isOnGround()
                && !attacker.isInsideVehicle()
                && !attacker.isSwimming()
                && attacker.getFallDistance() > 0.0f
                && !attacker.isInWater()
                && !attacker.isClimbing();
    }

    private void handleSin(Player attacker, Entity victim, SinConfig sin, boolean critical) {
        HitState state = sinHits
                .computeIfAbsent(attacker.getUniqueId(), key -> Collections.synchronizedMap(new HashMap<>()))
                .computeIfAbsent(sin.id, key -> new HitState());
        long now = System.currentTimeMillis();
        state.registerHit(now, sin.hitsWindowMs);

        Map<String, String> vars = buildVars(attacker, victim, sin, state.hits, critical);

        if (sin.warningMessage != null && !sin.warningMessage.isEmpty()) {
            if (sin.warningAlways || state.hits < sin.hitsRequired || sin.hitsRequired <= 1) {
                broadcastWarning(vars, sin.warningMessage);
            }
        }

        boolean meetsHits = state.hits >= sin.hitsRequired;
        boolean meetsCritical = !sin.criticalOnly || critical;
        if (!meetsHits || !meetsCritical) {
            return;
        }

        long cooldownMs = sin.cooldownMs >= 0 ? sin.cooldownMs : punishCooldownMs;
        if (!shouldPunish(attacker.getUniqueId(), sin.id, cooldownMs)) {
            return;
        }

        state.reset();
        dispatchBackup(attacker);
        Bukkit.getScheduler().runTask(this, () -> punishPlayer(attacker, sin, vars));
    }

    private Map<String, String> buildVars(Player attacker, Entity victim, SinConfig sin, int hits, boolean critical) {
        Map<String, String> vars = new HashMap<>();
        String nickname = resolveNickname(attacker);
        vars.put("player", attacker.getName());
        vars.put("nickname", nickname);
        vars.put("sin_id", sin.id);
        vars.put("sin_type", sin.type.name().toLowerCase(Locale.ROOT));
        vars.put("sin_reason", sin.reason == null ? "" : sin.reason);
        vars.put("sin_hits", Integer.toString(hits));
        vars.put("sin_hits_required", Integer.toString(sin.hitsRequired));
        vars.put("sin_critical", Boolean.toString(critical));
        vars.put("sin_world", attacker.getWorld().getName());
        if (victim != null) {
            vars.put("sin_victim", victim.getName());
            vars.put("sin_victim_type", victim.getType().name());
        } else {
            vars.put("sin_victim", "");
            vars.put("sin_victim_type", "");
        }
        return vars;
    }

    private void broadcastWarning(Map<String, String> vars, String message) {
        String text = replacePlaceholders(message, vars);
        Bukkit.broadcastMessage(colorize(text));
    }

    private String replacePlaceholders(String input, Map<String, String> vars) {
        String output = input;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            output = output.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return output;
    }

    private String resolveNickname(Player player) {
        String fallback = player.getDisplayName();
        try {
            Object essentials = Bukkit.getPluginManager().getPlugin("Essentials");
            if (essentials == null) {
                return fallback;
            }
            Class<?> essClass = Class.forName("com.earth2me.essentials.Essentials");
            if (!essClass.isInstance(essentials)) {
                return fallback;
            }
            Object user = essClass.getMethod("getUser", Player.class).invoke(essentials, player);
            if (user == null) {
                return fallback;
            }
            Object display = user.getClass().getMethod("getDisplayName").invoke(user);
            if (display instanceof String displayName && !displayName.isEmpty()) {
                return displayName;
            }
        } catch (Exception ignored) {
            return fallback;
        }
        return fallback;
    }

    private void dispatchBackup(Player player) {
        if (backupCommand == null || backupCommand.trim().isEmpty()) return;
        String cmd = backupCommand.replace("{player}", player.getName());
        ConsoleCommandSender console = Bukkit.getConsoleSender();
        Bukkit.dispatchCommand(console, cmd);
    }

    private void punishPlayer(Player player, SinConfig sin, Map<String, String> vars) {
        if (!player.isOnline()) return;
        PunishData data = capturePunishData(player);
        data.sinId = sin.id;
        data.inPurgatory = true;
        punishments.put(player.getUniqueId(), data);

        if (data.releaseTask != null) {
            data.releaseTask.cancel();
        }
        long delayTicks = Math.max(1L, releaseAfterMs / 50L);
        data.releaseTask = Bukkit.getScheduler().runTaskLater(this, () -> tryRelease(player.getUniqueId()), delayTicks);

        if (clearInventory) {
            player.getInventory().clear();
            player.getInventory().setArmorContents(null);
            player.setItemOnCursor(null);
            player.getInventory().setItemInOffHand(null);
        }
        if (clearXp) {
            player.setExp(0f);
            player.setLevel(0);
            player.setTotalExperience(0);
        }
        runHologramCommands(sin.hologramCommands, vars);
        callConditionalEvents(player, sin.ceEvents, vars);
        World purgWorld = getWorld(purgatoryWorld);
        if (purgWorld == null) return;
        Location target = cloneWithWorld(punishTarget, purgWorld);
        player.teleport(target);
        applyPunishEffects(player, data);
    }

    private PunishData capturePunishData(Player player) {
        PunishData data = new PunishData();
        data.origin = player.getLocation().clone();
        data.contents = cloneContents(player.getInventory().getContents());
        data.armor = cloneContents(player.getInventory().getArmorContents());
        ItemStack offhand = player.getInventory().getItemInOffHand();
        data.offhand = offhand == null ? null : offhand.clone();
        data.exp = player.getExp();
        data.level = player.getLevel();
        data.totalExp = player.getTotalExperience();
        data.releaseAt = System.currentTimeMillis() + releaseAfterMs;
        return data;
    }

    private ItemStack[] cloneContents(ItemStack[] items) {
        if (items == null) return null;
        ItemStack[] clone = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) {
            clone[i] = items[i] == null ? null : items[i].clone();
        }
        return clone;
    }

    private void tryRelease(UUID uuid) {
        PunishData data = punishments.get(uuid);
        if (data == null) return;
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline() || player.isDead()) {
            data.releaseReady = true;
            return;
        }
        releasePlayer(player, data);
    }

    private void releasePlayer(Player player, PunishData data) {
        Location target = resolveOrigin(data);
        if (target != null) {
            player.teleport(target);
        }
        Bukkit.getScheduler().runTask(this, () -> restoreInventory(player, data));
        if (!releaseMessage.isEmpty()) {
            player.sendMessage(releaseMessage);
        }
        clearBossBar(data);
        data.inPurgatory = false;
        punishments.remove(player.getUniqueId());
        scheduleResetIfEmpty();
    }

    private Location resolveOrigin(PunishData data) {
        if (data.origin != null && data.origin.getWorld() != null) {
            return data.origin;
        }
        World fallback = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        return fallback == null ? null : fallback.getSpawnLocation();
    }

    private void restoreInventory(Player player, PunishData data) {
        if (data.contents != null) {
            player.getInventory().setContents(data.contents);
        }
        if (data.armor != null) {
            player.getInventory().setArmorContents(data.armor);
        }
        player.getInventory().setItemInOffHand(data.offhand);
        player.setExp(data.exp);
        player.setLevel(data.level);
        player.setTotalExperience(data.totalExp);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerPortal(PlayerPortalEvent event) {
        if (!enablePortalRedirect) return;
        Player player = event.getPlayer();
        if (!player.getWorld().getName().equalsIgnoreCase(purgatoryWorld)) return;
        if (event.getCause() != PlayerPortalEvent.TeleportCause.NETHER_PORTAL) return;

        World purgWorld = getWorld(purgatoryWorld);
        if (purgWorld == null) return;
        Location target = cloneWithWorld(portalTarget, purgWorld);

        event.setCancelled(true);
        Bukkit.getScheduler().runTask(this, () -> player.teleport(target));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PunishData data = punishments.get(player.getUniqueId());
        if (data == null) return;
        long now = System.currentTimeMillis();
        if (now >= data.releaseAt || data.releaseReady) {
            Bukkit.getScheduler().runTask(this, () -> releasePlayer(player, data));
            return;
        }
        World purgWorld = getWorld(purgatoryWorld);
        if (purgWorld != null) {
            Location spawn = purgWorld.getSpawnLocation();
            Bukkit.getScheduler().runTask(this, () -> player.teleport(spawn));
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        PunishData data = punishments.get(player.getUniqueId());
        if (data == null) return;
        if (!player.getWorld().getName().equalsIgnoreCase(purgatoryWorld)) return;

        long now = System.currentTimeMillis();
        if (now < data.releaseAt && !data.releaseReady) {
            World purgWorld = getWorld(purgatoryWorld);
            if (purgWorld != null) {
                event.setRespawnLocation(purgWorld.getSpawnLocation());
            }
            return;
        }

        Location origin = resolveOrigin(data);
        if (origin != null) {
            event.setRespawnLocation(origin);
        }
        Bukkit.getScheduler().runTask(this, () -> restoreInventory(player, data));
        if (!releaseMessage.isEmpty()) {
            Bukkit.getScheduler().runTask(this, () -> player.sendMessage(releaseMessage));
        }
        clearBossBar(data);
        data.inPurgatory = false;
        punishments.remove(player.getUniqueId());
        scheduleResetIfEmpty();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!resetDeathStat) return;
        Player player = event.getEntity();
        if (!player.getWorld().getName().equalsIgnoreCase(purgatoryWorld)) return;

        event.setDeathMessage(null);

        int statAtDeath = player.getStatistic(Statistic.DEATHS);
        Bukkit.getScheduler().runTask(this, () -> {
            int statAfter = player.getStatistic(Statistic.DEATHS);
            int desired;
            if (statAfter > statAtDeath) {
                desired = statAtDeath;
            } else {
                desired = Math.max(0, statAtDeath - 1);
            }
            if (desired != statAfter) {
                player.setStatistic(Statistic.DEATHS, desired);
            }
        });
    }

    private Location cloneWithWorld(Location src, World world) {
        if (src == null) return null;
        return new Location(world, src.getX(), src.getY(), src.getZ(), src.getYaw(), src.getPitch());
    }

    private void applyPunishEffects(Player player, PunishData data) {
        if (titleConfig.enabled) {
            player.sendTitle(titleConfig.title, titleConfig.subtitle, titleConfig.fadeInTicks,
                    titleConfig.stayTicks, titleConfig.fadeOutTicks);
        }
        if (bossBarConfig.enabled) {
            BossBar bar = Bukkit.createBossBar(bossBarConfig.title, bossBarConfig.color, bossBarConfig.style);
            bar.setProgress(1.0);
            bar.addPlayer(player);
            data.bossBar = bar;
            long totalMs = Math.max(1L, releaseAfterMs);
            long maxDisplayMs = bossBarConfig.durationSeconds > 0
                    ? bossBarConfig.durationSeconds * 1000L
                    : totalMs;
            long barEndAt = Math.min(data.releaseAt, System.currentTimeMillis() + maxDisplayMs);
            data.bossBarTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
                long now = System.currentTimeMillis();
                if (now >= barEndAt || now >= data.releaseAt) {
                    clearBossBar(data);
                    return;
                }
                double remaining = Math.max(0L, data.releaseAt - now);
                double progress = remaining / (double) totalMs;
                data.bossBar.setProgress(Math.max(0.0, Math.min(1.0, progress)));
            }, 0L, 20L);
        }
    }

    private void clearBossBar(PunishData data) {
        if (data.bossBarTask != null) {
            data.bossBarTask.cancel();
            data.bossBarTask = null;
        }
        if (data.bossBar != null) {
            data.bossBar.removeAll();
            data.bossBar = null;
        }
    }

    private void runHologramCommands(List<String> commands, Map<String, String> vars) {
        if (commands == null || commands.isEmpty()) return;
        ConsoleCommandSender console = Bukkit.getConsoleSender();
        for (String raw : commands) {
            if (raw == null || raw.trim().isEmpty()) continue;
            String cmd = replacePlaceholders(raw, vars);
            Bukkit.dispatchCommand(console, cmd);
        }
    }

    private void callConditionalEvents(Player player, List<String> events, Map<String, String> vars) {
        if (events == null || events.isEmpty()) return;
        try {
            Class<?> storedVarClass = Class.forName("ce.ajneb97.model.StoredVariable");
            Class<?> callEventClass = Class.forName("ce.ajneb97.api.ConditionalEventsCallEvent");

            List<Object> storedVars = new ArrayList<>();
            for (Map.Entry<String, String> entry : vars.entrySet()) {
                storedVars.add(storedVarClass.getConstructor(String.class, String.class)
                        .newInstance(entry.getKey(), entry.getValue()));
            }

            for (String eventName : events) {
                Object evt = callEventClass
                        .getConstructor(Player.class, List.class, String.class)
                        .newInstance(player, storedVars, eventName);
                Bukkit.getPluginManager().callEvent((org.bukkit.event.Event) evt);
            }
        } catch (Exception ex) {
            getLogger().warning("Failed to call ConditionalEvents: " + ex.getMessage());
        }
    }

    private void scheduleResetIfEmpty() {
        if (!resetEnabled || !resetOnEmpty) return;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!isPurgatoryEmpty()) return;
            restorePurgatoryFromTemplate();
        }, 20L);
    }

    private boolean isPurgatoryEmpty() {
        World world = Bukkit.getWorld(purgatoryWorld);
        if (world == null) return true;
        if (!world.getPlayers().isEmpty()) return false;
        for (PunishData data : punishments.values()) {
            if (data != null && data.inPurgatory) {
                return false;
            }
        }
        return true;
    }

    private void restorePurgatoryFromTemplate() {
        if (resetTemplatePath == null || resetTemplatePath.trim().isEmpty()) {
            getLogger().warning("Purgatory reset skipped: template path not set.");
            return;
        }
        World world = Bukkit.getWorld(purgatoryWorld);
        if (world == null) return;
        if (!Bukkit.unloadWorld(world, false)) {
            getLogger().warning("Failed to unload purgatory for reset.");
            return;
        }

        Path template = Paths.get(resetTemplatePath);
        Path target = world.getWorldFolder().toPath();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                if (!Files.isDirectory(template)) {
                    getLogger().warning("Purgatory reset skipped: template path missing " + template);
                    return;
                }
                deleteRecursive(target);
                copyRecursive(template, target);
            } catch (IOException e) {
                getLogger().warning("Failed to restore purgatory: " + e.getMessage());
                return;
            }
            Bukkit.getScheduler().runTask(this, () -> Bukkit.createWorld(new WorldCreator(purgatoryWorld)));
        });
    }

    private void deleteRecursive(Path path) throws IOException {
        if (!Files.exists(path)) return;
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void copyRecursive(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(dir);
                Path dest = target.resolve(relative);
                Files.createDirectories(dest);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(file);
                Path dest = target.resolve(relative);
                Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static String colorize(String input) {
        if (input == null) {
            return "";
        }
        return ChatColor.translateAlternateColorCodes('&', input);
    }

    private enum SinType {
        PVP_HIT,
        ENTITY_HIT
    }

    private static class SinConfig {
        final String id;
        boolean enabled;
        SinType type;
        String reason;
        int hitsRequired;
        long hitsWindowMs;
        boolean criticalOnly;
        long cooldownMs;
        String warningMessage;
        boolean warningAlways;
        List<String> worlds;
        List<EntityType> entityTypes;
        List<String> ceEvents;
        List<String> hologramCommands;

        private SinConfig(String id) {
            this.id = id;
        }

        static SinConfig fromConfig(String id, ConfigurationSection section) {
            SinConfig sin = new SinConfig(id);
            sin.enabled = section.getBoolean("enabled", true);
            sin.type = parseType(section.getString("type", "pvp-hit"));
            sin.reason = section.getString("reason", id);
            sin.hitsRequired = Math.max(1, section.getInt("hits-required", 1));
            sin.hitsWindowMs = Math.max(0, section.getLong("hits-window-seconds", 10L)) * 1000L;
            sin.criticalOnly = section.getBoolean("critical-only", false);
            sin.cooldownMs = section.getLong("cooldown-ms", -1L);
            sin.warningMessage = section.getString("warning-message", "");
            sin.warningAlways = section.getBoolean("warning-always", true);
            sin.worlds = section.getStringList("worlds");

            sin.entityTypes = new ArrayList<>();
            for (String raw : section.getStringList("entities")) {
                EntityType type = parseEntityType(raw);
                if (type != null) {
                    sin.entityTypes.add(type);
                }
            }
            sin.ceEvents = section.getStringList("ce-events");
            sin.hologramCommands = section.getStringList("hologram-commands");
            return sin;
        }

        boolean appliesToWorld(String worldName, Set<String> defaultWorlds) {
            if (worlds != null && !worlds.isEmpty()) {
                return worlds.contains(worldName);
            }
            return defaultWorlds.contains(worldName);
        }

        boolean matches(EntityDamageByEntityEvent event, Entity victim) {
            return switch (type) {
                case PVP_HIT -> victim instanceof Player;
                case ENTITY_HIT -> entityTypes != null && entityTypes.contains(victim.getType());
            };
        }

        private static SinType parseType(String raw) {
            if (raw == null) return SinType.PVP_HIT;
            String normalized = raw.trim().toLowerCase(Locale.ROOT);
            if (normalized.equals("entity-hit") || normalized.equals("entity")) {
                return SinType.ENTITY_HIT;
            }
            return SinType.PVP_HIT;
        }

        private static EntityType parseEntityType(String raw) {
            if (raw == null) return null;
            try {
                return EntityType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }
    }

    private static class HitState {
        int hits;
        long lastHitAt;

        void registerHit(long now, long windowMs) {
            if (windowMs > 0 && lastHitAt > 0 && now - lastHitAt > windowMs) {
                hits = 0;
            }
            hits++;
            lastHitAt = now;
        }

        void reset() {
            hits = 0;
            lastHitAt = 0;
        }
    }

    private static class TitleConfig {
        boolean enabled;
        String title;
        String subtitle;
        int fadeInTicks;
        int stayTicks;
        int fadeOutTicks;

        static TitleConfig fromConfig(ConfigurationSection section) {
            TitleConfig cfg = new TitleConfig();
            if (section == null) {
                cfg.enabled = false;
                cfg.title = "";
                cfg.subtitle = "";
                cfg.fadeInTicks = 10;
                cfg.stayTicks = 140;
                cfg.fadeOutTicks = 20;
                return cfg;
            }
            cfg.enabled = section.getBoolean("enabled", true);
            cfg.title = colorize(section.getString("title", ""));
            cfg.subtitle = colorize(section.getString("subtitle", ""));
            cfg.fadeInTicks = Math.max(0, section.getInt("fade-in-ticks", 10));
            cfg.stayTicks = Math.max(0, section.getInt("stay-ticks", 140));
            cfg.fadeOutTicks = Math.max(0, section.getInt("fade-out-ticks", 20));
            return cfg;
        }
    }

    private static class BossBarConfig {
        boolean enabled;
        String title;
        BarColor color;
        BarStyle style;
        long durationSeconds;

        static BossBarConfig fromConfig(ConfigurationSection section) {
            BossBarConfig cfg = new BossBarConfig();
            if (section == null) {
                cfg.enabled = false;
                cfg.title = "";
                cfg.color = BarColor.RED;
                cfg.style = BarStyle.SOLID;
                cfg.durationSeconds = 7L;
                return cfg;
            }
            cfg.enabled = section.getBoolean("enabled", true);
            cfg.title = colorize(section.getString("title", ""));
            cfg.color = parseColor(section.getString("color", "RED"));
            cfg.style = parseStyle(section.getString("style", "SOLID"));
            cfg.durationSeconds = Math.max(1L, section.getLong("duration-seconds", 7L));
            return cfg;
        }

        private static BarColor parseColor(String raw) {
            if (raw == null) return BarColor.RED;
            try {
                return BarColor.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                return BarColor.RED;
            }
        }

        private static BarStyle parseStyle(String raw) {
            if (raw == null) return BarStyle.SOLID;
            try {
                return BarStyle.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                return BarStyle.SOLID;
            }
        }
    }

    private static class PunishData {
        String sinId;
        Location origin;
        ItemStack[] contents;
        ItemStack[] armor;
        ItemStack offhand;
        float exp;
        int level;
        int totalExp;
        long releaseAt;
        boolean releaseReady;
        BukkitTask releaseTask;
        BossBar bossBar;
        BukkitTask bossBarTask;
        boolean inPurgatory;
    }
}
