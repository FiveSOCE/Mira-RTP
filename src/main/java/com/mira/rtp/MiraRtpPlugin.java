package com.mira.rtp;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class MiraRtpPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {
    private final Random random = new Random();
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private FactionsBridge factionsBridge;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        factionsBridge = new FactionsBridge(this);
        if (getCommand("rtp") != null) {
            getCommand("rtp").setExecutor(this);
            getCommand("rtp").setTabCompleter(this);
        }
        getLogger().info("MiraRTP v" + getDescription().getVersion() + " enabled.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("mirartp.admin")) return true;
            reloadConfig();
            factionsBridge = new FactionsBridge(this);
            msg(sender, getConfig().getString("messages.reload", "&aMiraRTP configuration reloaded."));
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (!player.hasPermission("mirartp.use")) return true;

        long now = System.currentTimeMillis();
        long cooldownMs = Math.max(0, getConfig().getLong("cooldown-seconds", 300)) * 1000L;
        long ready = cooldowns.getOrDefault(player.getUniqueId(), 0L);
        if (!player.hasPermission("mirartp.bypass.cooldown") && ready > now) {
            long seconds = Math.max(1, (ready - now + 999L) / 1000L);
            msg(player, getConfig().getString("messages.cooldown", "&cYou must wait &f%seconds%s &cbefore using RTP again.")
                    .replace("%seconds%", String.valueOf(seconds)));
            return true;
        }

        String worldName = getConfig().getString("target-world", "factions");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            msg(player, getConfig().getString("messages.world-missing", "&cThe configured RTP world &f%world% &cis not loaded.")
                    .replace("%world%", worldName));
            return true;
        }

        msg(player, getConfig().getString("messages.searching", "&7Searching for a safe location in &f%world%&7...")
                .replace("%world%", world.getName()));
        search(player, world, 0, Math.max(1, getConfig().getInt("attempts", 40)), cooldownMs);
        return true;
    }

    private void search(Player player, World world, int attempt, int maxAttempts, long cooldownMs) {
        if (!player.isOnline()) return;
        if (attempt >= maxAttempts) {
            msg(player, getConfig().getString("messages.no-location", "&cCould not find a safe wilderness location. Try again."));
            return;
        }

        Candidate candidate = randomCandidate(world);
        if (candidate == null) {
            msg(player, getConfig().getString("messages.no-location", "&cCould not find a safe wilderness location. Try again."));
            return;
        }

        world.getChunkAtAsync(candidate.x >> 4, candidate.z >> 4, true).whenComplete((chunk, throwable) ->
                Bukkit.getScheduler().runTask(this, () -> {
                    if (throwable != null || chunk == null || !player.isOnline()) {
                        search(player, world, attempt + 1, maxAttempts, cooldownMs);
                        return;
                    }
                    Location safe = safeLocation(world, candidate.x, candidate.z);
                    if (safe == null || !territoryAllowed(safe)) {
                        search(player, world, attempt + 1, maxAttempts, cooldownMs);
                        return;
                    }
                    player.teleportAsync(safe).whenComplete((success, teleportError) -> Bukkit.getScheduler().runTask(this, () -> {
                        if (Boolean.TRUE.equals(success) && teleportError == null) {
                            if (!player.hasPermission("mirartp.bypass.cooldown") && cooldownMs > 0) {
                                cooldowns.put(player.getUniqueId(), System.currentTimeMillis() + cooldownMs);
                            }
                            msg(player, getConfig().getString("messages.success", "&aTeleported to wilderness at &f%x%&7, &f%z%&a.")
                                    .replace("%x%", String.valueOf(safe.getBlockX()))
                                    .replace("%z%", String.valueOf(safe.getBlockZ())));
                        } else {
                            search(player, world, attempt + 1, maxAttempts, cooldownMs);
                        }
                    }));
                }));
    }

    private Candidate randomCandidate(World world) {
        int min = Math.max(0, getConfig().getInt("radius.min", 500));
        int max = Math.max(min + 1, getConfig().getInt("radius.max", 10000));
        double angle = random.nextDouble() * Math.PI * 2.0;
        double radius = Math.sqrt(random.nextDouble() * (max * (double) max - min * (double) min) + min * (double) min);
        int x = (int) Math.round(Math.cos(angle) * radius);
        int z = (int) Math.round(Math.sin(angle) * radius);

        if (getConfig().getBoolean("world-border.respect", true)) {
            WorldBorder border = world.getWorldBorder();
            Location center = border.getCenter();
            double half = border.getSize() / 2.0;
            double padding = Math.max(0, getConfig().getDouble("world-border.padding", 16));
            if (x < center.getX() - half + padding || x > center.getX() + half - padding
                    || z < center.getZ() - half + padding || z > center.getZ() + half - padding) {
                return null;
            }
        }
        return new Candidate(x, z);
    }

    private Location safeLocation(World world, int x, int z) {
        int highest = world.getHighestBlockYAt(x, z);
        if (highest <= world.getMinHeight() || highest >= world.getMaxHeight() - 2) return null;

        Block floor = world.getBlockAt(x, highest - 1, z);
        Block feet = world.getBlockAt(x, highest, z);
        Block head = world.getBlockAt(x, highest + 1, z);
        Material material = floor.getType();

        if (!floor.getType().isSolid() || !feet.isPassable() || !head.isPassable()) return null;
        if (material == Material.BEDROCK) return null;
        if (getConfig().getBoolean("safety.avoid-water", true) && (material == Material.WATER || feet.getType() == Material.WATER)) return null;
        if (getConfig().getBoolean("safety.avoid-lava", true) && (material == Material.LAVA || feet.getType() == Material.LAVA)) return null;
        if (getConfig().getBoolean("safety.avoid-cactus", true) && material == Material.CACTUS) return null;
        if (getConfig().getBoolean("safety.avoid-powder-snow", true) && material == Material.POWDER_SNOW) return null;
        if (getConfig().getBoolean("safety.avoid-magma", true) && material == Material.MAGMA_BLOCK) return null;
        if (getConfig().getBoolean("safety.avoid-leaves", true) && material.name().endsWith("_LEAVES")) return null;

        return new Location(world, x + 0.5, highest, z + 0.5, random.nextFloat() * 360.0f, 0.0f);
    }

    private boolean territoryAllowed(Location location) {
        if (!factionsBridge.available()) return true;
        if (getConfig().getBoolean("safety.avoid-safezone", true) && factionsBridge.isSafeZone(location)) return false;
        if (getConfig().getBoolean("safety.avoid-warzone", true) && factionsBridge.isWarZone(location)) return false;
        return !getConfig().getBoolean("safety.avoid-claims", true) || factionsBridge.territoryFaction(location).isEmpty();
    }

    private void msg(CommandSender sender, String message) {
        String prefix = getConfig().getString("messages.prefix", "&d&lMiraRTP &8» &r");
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', prefix + message));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && sender.hasPermission("mirartp.admin") && "reload".startsWith(args[0].toLowerCase(Locale.ROOT))) {
            return List.of("reload");
        }
        return Collections.emptyList();
    }

    private record Candidate(int x, int z) {}

    private static final class FactionsBridge {
        private final JavaPlugin plugin;
        private Object api;
        private Method territoryFaction;
        private Method isSafeZone;
        private Method isWarZone;

        private FactionsBridge(JavaPlugin plugin) {
            this.plugin = plugin;
            hook();
        }

        private void hook() {
            try {
                if (!Bukkit.getPluginManager().isPluginEnabled("MiraFactions")) return;
                Class<?> apiClass = Class.forName("com.mira.factions.api.MiraFactionsApi");
                @SuppressWarnings({"rawtypes", "unchecked"})
                RegisteredServiceProvider<?> registration = Bukkit.getServicesManager().getRegistration((Class) apiClass);
                if (registration == null) return;
                api = registration.getProvider();
                territoryFaction = apiClass.getMethod("territoryFaction", Location.class);
                isSafeZone = apiClass.getMethod("isSafeZone", Location.class);
                isWarZone = apiClass.getMethod("isWarZone", Location.class);
                plugin.getLogger().info("Hooked MiraFactions wilderness checks.");
            } catch (ReflectiveOperationException ex) {
                plugin.getLogger().warning("MiraFactions detected but its public API could not be hooked: " + ex.getMessage());
                api = null;
            }
        }

        private boolean available() { return api != null; }

        private Optional<?> territoryFaction(Location location) {
            try {
                Object value = territoryFaction.invoke(api, location);
                return value instanceof Optional<?> optional ? optional : Optional.empty();
            } catch (ReflectiveOperationException ex) {
                return Optional.empty();
            }
        }

        private boolean isSafeZone(Location location) {
            return invokeBoolean(isSafeZone, location);
        }

        private boolean isWarZone(Location location) {
            return invokeBoolean(isWarZone, location);
        }

        private boolean invokeBoolean(Method method, Location location) {
            try {
                return Boolean.TRUE.equals(method.invoke(api, location));
            } catch (ReflectiveOperationException ex) {
                return false;
            }
        }
    }
}
