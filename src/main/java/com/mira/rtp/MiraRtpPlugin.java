package com.mira.rtp;

import com.mira.core.api.MiraCore;
import com.mira.core.api.MiraCoreProvider;
import com.mira.core.api.ModuleHealth;
import com.mira.factions.api.MiraFactionsApi;
import com.mira.rtp.api.event.RtpTeleportEvent;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class MiraRtpPlugin extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {
    private static final String COOLDOWN_KEY = "mirartp.use";

    private final Set<UUID> searching = ConcurrentHashMap.newKeySet();
    private Set<Material> blockedMaterials = Set.of();

    private MiraCore core;
    private MiraFactionsApi factions;
    private RtpApi api;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        core = MiraCoreProvider.require();
        RegisteredServiceProvider<MiraFactionsApi> registration =
                getServer().getServicesManager().getRegistration(MiraFactionsApi.class);
        if (registration == null || registration.getProvider() == null) {
            throw new IllegalStateException("MiraFactions API is required for MiraRTP.");
        }
        factions = registration.getProvider();

        reloadSafetyMaterials();

        var rtpCommand = getCommand("rtp");
        if (rtpCommand != null) {
            rtpCommand.setExecutor(this);
            rtpCommand.setTabCompleter(this);
        }

        api = new RtpApiImpl();
        getServer().getServicesManager().register(RtpApi.class, api, this, ServicePriority.Normal);
        core.services().register(RtpApi.class, api);
        core.modules().register(this, "MiraRTP");
        core.modules().setHealth(this, ModuleHealth.HEALTHY,
                "Async safe-location search, MiraFactions wilderness filtering and Core cooldown integration ready");

        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("MiraRTP v" + getPluginMeta().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
        searching.clear();
        getServer().getServicesManager().unregisterAll(this);
        if (core != null) {
            if (api != null) core.services().unregister(RtpApi.class, api);
            core.modules().unregister(this);
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("mirartp.admin")) {
                msg(sender, "&cYou do not have permission.");
                return true;
            }
            reloadConfig();
            reloadSafetyMaterials();
            msg(sender, getConfig().getString("messages.reload", "&aMiraRTP configuration reloaded."));
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("status")) {
            if (!(sender instanceof Player player)) {
                msg(sender, "&cPlayers only.");
                return true;
            }
            sendStatus(player);
            return true;
        }

        if (!(sender instanceof Player player)) {
            msg(sender, "&cPlayers only.");
            return true;
        }

        startRequest(player);
        return true;
    }

    private boolean startRequest(Player player) {
        if (!player.hasPermission("mirartp.use")) {
            msg(player, "&cYou do not have permission.");
            return false;
        }

        UUID playerId = player.getUniqueId();
        if (!searching.add(playerId)) {
            msg(player, getConfig().getString("messages.already-searching",
                    "&eAn RTP search is already running for you."));
            return false;
        }

        if (!player.hasPermission("mirartp.bypass.cooldown")
                && core.cooldowns().active(playerId, COOLDOWN_KEY)) {
            searching.remove(playerId);
            long seconds = Math.max(1L, core.cooldowns().remaining(playerId, COOLDOWN_KEY).toSeconds());
            msg(player, getConfig().getString("messages.cooldown",
                            "&cYou must wait &f%seconds%s &cbefore using RTP again.")
                    .replace("%seconds%", Long.toString(seconds)));
            return false;
        }

        String worldName = getConfig().getString("target-world", "factions");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            searching.remove(playerId);
            msg(player, getConfig().getString("messages.world-missing",
                            "&cThe configured RTP world &f%world% &cis not loaded.")
                    .replace("%world%", worldName));
            return false;
        }

        int maxAttempts = Math.max(1, Math.min(500, getConfig().getInt("attempts", 40)));
        msg(player, getConfig().getString("messages.searching",
                        "&7Searching for a safe location in &f%world%&7...")
                .replace("%world%", world.getName()));

        search(playerId, world.getUID(), 0, maxAttempts);
        return true;
    }

    private void search(UUID playerId, UUID worldId, int attempt, int maxAttempts) {
        Player player = Bukkit.getPlayer(playerId);
        World world = Bukkit.getWorld(worldId);

        if (player == null || !player.isOnline() || world == null) {
            searching.remove(playerId);
            return;
        }

        if (attempt >= maxAttempts) {
            searching.remove(playerId);
            msg(player, getConfig().getString("messages.no-location",
                    "&cCould not find a safe wilderness location. Try again."));
            return;
        }

        Candidate candidate = randomCandidate(world);
        if (candidate == null) {
            search(playerId, worldId, attempt + 1, maxAttempts);
            return;
        }

        world.getChunkAtAsync(candidate.x() >> 4, candidate.z() >> 4, true)
                .whenComplete((chunk, throwable) -> Bukkit.getScheduler().runTask(this, () -> {
                    Player current = Bukkit.getPlayer(playerId);
                    World currentWorld = Bukkit.getWorld(worldId);
                    if (current == null || !current.isOnline() || currentWorld == null) {
                        searching.remove(playerId);
                        return;
                    }
                    if (throwable != null || chunk == null) {
                        search(playerId, worldId, attempt + 1, maxAttempts);
                        return;
                    }

                    Location safe = safeLocation(currentWorld, candidate.x(), candidate.z());
                    if (safe == null || !territoryAllowed(safe)) {
                        search(playerId, worldId, attempt + 1, maxAttempts);
                        return;
                    }

                    current.teleportAsync(safe).whenComplete((success, teleportError) ->
                            Bukkit.getScheduler().runTask(this, () -> {
                                Player online = Bukkit.getPlayer(playerId);
                                if (online == null || !online.isOnline()) {
                                    searching.remove(playerId);
                                    return;
                                }

                                if (!Boolean.TRUE.equals(success) || teleportError != null) {
                                    search(playerId, worldId, attempt + 1, maxAttempts);
                                    return;
                                }

                                searching.remove(playerId);

                                long cooldownSeconds = Math.max(0L,
                                        getConfig().getLong("cooldown-seconds", 300L));
                                if (!online.hasPermission("mirartp.bypass.cooldown") && cooldownSeconds > 0L) {
                                    core.cooldowns().start(playerId, COOLDOWN_KEY,
                                            Duration.ofSeconds(cooldownSeconds));
                                }

                                Bukkit.getPluginManager().callEvent(
                                        new RtpTeleportEvent(online, safe.clone(), attempt + 1));

                                if (getConfig().getBoolean("audit.successful-teleports", true)) {
                                    core.audit().record("MiraRTP", "RTP_TELEPORT",
                                            playerId, online.getName(), playerId.toString(),
                                            "Random teleport completed",
                                            Map.of(
                                                    "world", safe.getWorld().getName(),
                                                    "x", Integer.toString(safe.getBlockX()),
                                                    "y", Integer.toString(safe.getBlockY()),
                                                    "z", Integer.toString(safe.getBlockZ()),
                                                    "attempts", Integer.toString(attempt + 1)
                                            ));
                                }

                                msg(online, getConfig().getString("messages.success",
                                                "&aTeleported to wilderness at &f%x%&7, &f%z%&a.")
                                        .replace("%x%", Integer.toString(safe.getBlockX()))
                                        .replace("%z%", Integer.toString(safe.getBlockZ())));
                            }));
                }));
    }

    private Candidate randomCandidate(World world) {
        int min = Math.max(0, getConfig().getInt("radius.min", 500));
        int max = Math.max(min + 1, getConfig().getInt("radius.max", 10000));

        Location center = resolveCenter(world);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double angle = random.nextDouble(0D, Math.PI * 2D);
        double radiusSquared = random.nextDouble(
                min * (double) min,
                max * (double) max);
        double distance = Math.sqrt(radiusSquared);

        int x = (int) Math.round(center.getX() + Math.cos(angle) * distance);
        int z = (int) Math.round(center.getZ() + Math.sin(angle) * distance);

        if (getConfig().getBoolean("world-border.respect", true)) {
            WorldBorder border = world.getWorldBorder();
            Location borderCenter = border.getCenter();
            double half = border.getSize() / 2D;
            double padding = Math.max(0D, getConfig().getDouble("world-border.padding", 16D));
            double availableHalf = half - padding;
            if (availableHalf <= 0D) return null;

            if (x < borderCenter.getX() - availableHalf || x > borderCenter.getX() + availableHalf
                    || z < borderCenter.getZ() - availableHalf || z > borderCenter.getZ() + availableHalf) {
                return null;
            }
        }

        return new Candidate(x, z);
    }

    private Location resolveCenter(World world) {
        String mode = getConfig().getString("radius.center", "WORLD_BORDER")
                .trim().toUpperCase(Locale.ROOT);

        return switch (mode) {
            case "WORLD_SPAWN", "SPAWN" -> world.getSpawnLocation();
            case "CUSTOM" -> new Location(world,
                    getConfig().getDouble("radius.custom-x", 0D),
                    world.getSeaLevel(),
                    getConfig().getDouble("radius.custom-z", 0D));
            default -> world.getWorldBorder().getCenter();
        };
    }

    private Location safeLocation(World world, int x, int z) {
        int highest = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        if (highest < world.getMinHeight() || highest >= world.getMaxHeight() - 2) return null;

        Block floor = world.getBlockAt(x, highest, z);
        Block feet = world.getBlockAt(x, highest + 1, z);
        Block head = world.getBlockAt(x, highest + 2, z);

        if (!floor.getType().isSolid() || !feet.isPassable() || !head.isPassable()) return null;
        if (feet.isLiquid() || head.isLiquid()) return null;

        if (blocked(floor.getType()) || blocked(feet.getType()) || blocked(head.getType())) return null;

        if (getConfig().getBoolean("safety.avoid-tree-tops", true)) {
            String floorName = floor.getType().name();
            if (floorName.endsWith("_LOG") || floorName.endsWith("_WOOD")
                    || floorName.endsWith("_STEM") || floorName.endsWith("_HYPHAE")) {
                return null;
            }
        }

        Location result = new Location(world, x + 0.5D, highest + 1D, z + 0.5D,
                ThreadLocalRandom.current().nextFloat() * 360F, 0F);

        if (!result.getWorld().getWorldBorder().isInside(result)
                && getConfig().getBoolean("world-border.respect", true)) {
            return null;
        }

        return result;
    }

    private boolean blocked(Material material) {
        if (material == null) return true;

        if (getConfig().getBoolean("safety.avoid-water", true) && material == Material.WATER) return true;
        if (getConfig().getBoolean("safety.avoid-lava", true) && material == Material.LAVA) return true;
        if (getConfig().getBoolean("safety.avoid-leaves", true) && material.name().endsWith("_LEAVES")) return true;
        if (getConfig().getBoolean("safety.avoid-cactus", true) && material == Material.CACTUS) return true;
        if (getConfig().getBoolean("safety.avoid-powder-snow", true) && material == Material.POWDER_SNOW) return true;
        if (getConfig().getBoolean("safety.avoid-magma", true) && material == Material.MAGMA_BLOCK) return true;

        return blockedMaterials.contains(material);
    }

    private boolean territoryAllowed(Location location) {
        if (getConfig().getBoolean("safety.avoid-safezone", true) && factions.isSafeZone(location)) return false;
        if (getConfig().getBoolean("safety.avoid-warzone", true) && factions.isWarZone(location)) return false;
        return !getConfig().getBoolean("safety.avoid-claims", true)
                || factions.territoryFaction(location).isEmpty();
    }

    private void reloadSafetyMaterials() {
        Set<Material> blocked = EnumSet.noneOf(Material.class);
        for (String raw : getConfig().getStringList("safety.blocked-materials")) {
            Material material = Material.matchMaterial(raw);
            if (material == null) {
                getLogger().warning("Ignoring unknown RTP blocked material: " + raw);
                continue;
            }
            blocked.add(material);
        }
        blockedMaterials = Set.copyOf(blocked);
    }

    private void sendStatus(Player player) {
        if (searching.contains(player.getUniqueId())) {
            msg(player, "&eAn RTP search is currently running.");
            return;
        }

        if (player.hasPermission("mirartp.bypass.cooldown")) {
            msg(player, "&aRTP is ready. &7Cooldown bypass active.");
            return;
        }

        if (!core.cooldowns().active(player.getUniqueId(), COOLDOWN_KEY)) {
            msg(player, "&aRTP is ready.");
            return;
        }

        long seconds = Math.max(1L,
                core.cooldowns().remaining(player.getUniqueId(), COOLDOWN_KEY).toSeconds());
        msg(player, "&eRTP cooldown remaining: &f" + seconds + "s&e.");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        searching.remove(event.getPlayer().getUniqueId());
    }

    private void msg(CommandSender sender, String message) {
        core.messages().send(sender, message);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length != 1) return List.of();

        List<String> values = new ArrayList<>(List.of("status"));
        if (sender.hasPermission("mirartp.admin")) values.add("reload");

        String lower = args[0].toLowerCase(Locale.ROOT);
        return values.stream()
                .filter(value -> value.startsWith(lower))
                .sorted()
                .toList();
    }

    public interface RtpApi {
        boolean request(Player player);
        boolean searching(UUID player);
        Duration remainingCooldown(UUID player);
    }

    private final class RtpApiImpl implements RtpApi {
        @Override public boolean request(Player player) { return startRequest(player); }
        @Override public boolean searching(UUID player) { return MiraRtpPlugin.this.searching.contains(player); }

        @Override
        public Duration remainingCooldown(UUID player) {
            return core.cooldowns().active(player, COOLDOWN_KEY)
                    ? core.cooldowns().remaining(player, COOLDOWN_KEY)
                    : Duration.ZERO;
        }
    }

    private record Candidate(int x, int z) { }
}
