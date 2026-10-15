package de.mmoonlight.pooandpee;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PooAndPee extends JavaPlugin implements Listener {

    private Map<UUID, Long> peeCooldowns = new HashMap<>();
    private Map<UUID, Long> pooCooldowns = new HashMap<>();
    private static final Pattern HEX_PATTERN = Pattern.compile("#([A-Fa-f0-9]{6})");
    private boolean isLegacyVersion = false;
    private boolean supportsHexColors = false;
    private Material peeMaterial = null;
    private Material pooMaterial = null;
    // Legacy (pre-flattening) durability values, -1 when not applicable.
    private short peeWoolData = -1;
    private short pooWoolData = -1;
    private boolean pluginEnabled = true;
    private FileConfiguration permsConfig = null;
    private File permsFile = null;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        setupPermsConfig();

        String version = getServer().getBukkitVersion();

        // Minimum supported version is 1.8: ItemMergeEvent does not exist before it.
        isLegacyVersion = version.matches(".*1\\.(8|9|1[0-2])[^0-9].*");

        // Hex chat colors were introduced with the 1.16 chat overhaul. Probe the
        // API instead of parsing the version string, so future versions
        // (1.21, 1.22, ...) keep working without touching this code.
        supportsHexColors = isHexSupportedByApi();

        if (!supportsHexColors && configContainsHexColors()) {
            getLogger().severe("Your config contains hex colors (#RRGGBB), but your server version doesn't support them!");
            getLogger().severe("Please use standard color codes (&e, &c, etc.) for your server version.");
            getLogger().severe("Hex colors are only supported on Minecraft 1.16+");
            pluginEnabled = false;
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        if (!setupMaterials()) {
            getLogger().severe("Failed to initialize materials. Plugin will be disabled.");
            getLogger().severe("Please check your config.yml and set valid materials for your server version.");
            pluginEnabled = false;
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(this, this);

        registerCommands();
    }

    private void registerCommands() {
        if (getCommand("pee") != null) {
            getCommand("pee").setExecutor(new PeeCommand());
        }
        if (getCommand("poo") != null) {
            getCommand("poo").setExecutor(new PooCommand());
        }
        if (getCommand("pooandpee") != null) {
            getCommand("pooandpee").setExecutor(new ReloadCommand());
        }
    }

    /**
     * Detects hex color support by looking for {@code ChatColor#of(String)}
     * instead of hardcoding a list of released versions.
     */
    private boolean isHexSupportedByApi() {
        try {
            net.md_5.bungee.api.ChatColor.class.getMethod("of", String.class);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void setupPermsConfig() {
        permsFile = new File(getDataFolder(), "perms.yml");
        if (!permsFile.exists()) {
            saveResource("perms.yml", false);
        }
        permsConfig = YamlConfiguration.loadConfiguration(permsFile);

        if (!permsConfig.contains("pee")) {
            permsConfig.set("pee", "all");
        }
        if (!permsConfig.contains("poo")) {
            permsConfig.set("poo", "all");
        }
        if (!permsConfig.contains("reload")) {
            permsConfig.set("reload", "pooandpee.admin");
        }

        try {
            permsConfig.save(permsFile);
        } catch (IOException e) {
            getLogger().severe("Could not save perms.yml: " + e.getMessage());
        }
    }

    public void reloadPlugin() {
        reloadConfig();
        setupPermsConfig();
        supportsHexColors = isHexSupportedByApi();
        if (!setupMaterials()) {
            getLogger().severe("Reload failed: materials are invalid. Disabling plugin.");
            pluginEnabled = false;
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        pluginEnabled = true;
    }

    private boolean hasPermission(Player player, String type) {
        if (player.isOp()) {
            return true;
        }

        String permission = permsConfig.getString(type, "all");

        if (permission.equalsIgnoreCase("all")) {
            return true;
        }

        return player.hasPermission(permission);
    }

    /**
     * Scans every message in the config, so hex colors are detected no matter
     * which message the user customized.
     */
    private boolean configContainsHexColors() {
        ConfigurationSection messages = getConfig().getConfigurationSection("messages");
        if (messages == null) {
            return false;
        }

        for (String key : messages.getKeys(false)) {
            String value = messages.getString(key);
            if (value != null && HEX_PATTERN.matcher(value).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Maps a modern material name onto a legacy equivalent where possible.
     * Colored wool does not exist before the 1.13 "flattening"; it was encoded
     * as {@code WOOL} plus a durability value.
     */
    private Material resolveMaterial(String rawName, boolean isPee) {
        String name = rawName.trim().toUpperCase().replace(' ', '_');

        try {
            return Material.valueOf(name);
        } catch (Exception ignored) {
            // fall through to legacy handling
        }

        if (isLegacyVersion && name.endsWith("_WOOL")) {
            short data = legacyWoolData(name.substring(0, name.length() - "_WOOL".length()));
            if (data >= 0) {
                if (isPee) {
                    peeWoolData = data;
                } else {
                    pooWoolData = data;
                }
                getLogger().info("Legacy mode: using WOOL (data " + data + ") for material '" + rawName + "'");
                return Material.valueOf("WOOL");
            }
        }

        return null;
    }

    private short legacyWoolData(String color) {
        switch (color) {
            case "WHITE": return 0;
            case "ORANGE": return 1;
            case "MAGENTA": return 2;
            case "LIGHT_BLUE": return 3;
            case "YELLOW": return 4;
            case "LIME": return 5;
            case "PINK": return 6;
            case "GRAY": return 7;
            case "LIGHT_GRAY": return 8;
            case "CYAN": return 9;
            case "PURPLE": return 10;
            case "BLUE": return 11;
            case "BROWN": return 12;
            case "GREEN": return 13;
            case "RED": return 14;
            case "BLACK": return 15;
            default: return -1;
        }
    }

    private boolean setupMaterials() {
        String peeMaterialName = getConfig().getString("items.pee.material");
        String pooMaterialName = getConfig().getString("items.poo.material");

        peeWoolData = -1;
        pooWoolData = -1;

        if (peeMaterialName == null || peeMaterialName.trim().isEmpty()) {
            getLogger().severe("Pee material is not specified in config!");
            getLogger().severe("Please add a valid material name to items.pee.material in config.yml");
            return false;
        }

        if (pooMaterialName == null || pooMaterialName.trim().isEmpty()) {
            getLogger().severe("Poo material is not specified in config!");
            getLogger().severe("Please add a valid material name to items.poo.material in config.yml");
            return false;
        }

        peeMaterial = resolveMaterial(peeMaterialName, true);
        if (peeMaterial == null) {
            getLogger().severe("Invalid material name in config: " + peeMaterialName);
            getLogger().severe("Material '" + peeMaterialName + "' does not exist in '" + getServer().getBukkitVersion() + "'");
            return false;
        }

        pooMaterial = resolveMaterial(pooMaterialName, false);
        if (pooMaterial == null) {
            getLogger().severe("Invalid material name in config: " + pooMaterialName);
            getLogger().severe("Material '" + pooMaterialName + "' does not exist in '" + getServer().getBukkitVersion() + "'");
            return false;
        }

        getLogger().info("Successfully set pee material to: " + peeMaterialName);
        getLogger().info("Successfully set poo material to: " + pooMaterialName);
        return true;
    }

    @Override
    public void onDisable() {
        getLogger().info("Poo and Pee plugin disabled");
    }

    private boolean isPluginItem(ItemStack item) {
        if (item == null || item.getType() == null) {
            return false;
        }
        return item.getType() == peeMaterial || item.getType() == pooMaterial;
    }

    @EventHandler
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        if (!pluginEnabled || event == null || !(event.getEntity() instanceof Player)) {
            return;
        }

        if (!getConfig().getBoolean("settings.block-pickup", true)) {
            return;
        }

        ItemStack item = event.getItem().getItemStack();

        if (isPluginItem(item)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onItemMerge(ItemMergeEvent event) {
        if (!pluginEnabled || event == null) {
            return;
        }

        if (!getConfig().getBoolean("settings.block-merge", true)) {
            return;
        }

        ItemStack item = event.getEntity().getItemStack();

        if (isPluginItem(item)) {
            event.setCancelled(true);
        }
    }

    private String formatColors(String text) {
        if (text == null) return "";

        if (supportsHexColors) {
            try {
                Matcher matcher = HEX_PATTERN.matcher(text);
                StringBuffer buffer = new StringBuffer();

                while (matcher.find()) {
                    String hexColor = matcher.group(1);
                    matcher.appendReplacement(buffer, Matcher.quoteReplacement(net.md_5.bungee.api.ChatColor.of("#" + hexColor).toString()));
                }
                matcher.appendTail(buffer);

                return ChatColor.translateAlternateColorCodes('&', buffer.toString());
            } catch (Exception | NoSuchMethodError e) {
                getLogger().warning("Error processing hex color: " + e.getMessage());
                return ChatColor.translateAlternateColorCodes('&', text);
            }
        } else {
            if (HEX_PATTERN.matcher(text).find()) {
                getLogger().warning("Attempted to use hex colors on a server that doesn't support them!");
                return ChatColor.RED + "Error: Hex colors not supported on this server version!";
            }
            return ChatColor.translateAlternateColorCodes('&', text);
        }
    }

    private ItemStack createItemStack(boolean isPee) {
        try {
            ItemStack stack = new ItemStack(isPee ? peeMaterial : pooMaterial);
            short data = isPee ? peeWoolData : pooWoolData;
            if (data >= 0) {
                stack.setDurability(data);
            }
            return stack;
        } catch (Exception e) {
            getLogger().severe("Failed to create item: " + e.getMessage());
            return new ItemStack(Material.valueOf("STONE"));
        }
    }

    private class PeeCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!pluginEnabled) {
                sender.sendMessage(ChatColor.RED + "This plugin is not properly configured. Please check the server logs.");
                return true;
            }

            if (!(sender instanceof Player)) {
                String message = getConfig().getString("messages.only-players", "&cOnly players can use this command!");
                sender.sendMessage(formatColors(message));
                return true;
            }

            Player player = (Player) sender;

            if (!hasPermission(player, "pee")) {
                String message = getConfig().getString("messages.no-permission", "&cYou don't have permission to use this command!");
                player.sendMessage(formatColors(message));
                return true;
            }

            UUID playerUUID = player.getUniqueId();

            int cooldownTime = getConfig().getInt("cooldowns.pee", 60);
            if (peeCooldowns.containsKey(playerUUID)) {
                long secondsLeft = ((peeCooldowns.get(playerUUID) / 1000) + cooldownTime) - (System.currentTimeMillis() / 1000);
                if (secondsLeft > 0) {
                    String cooldownMessage = getConfig().getString("messages.cooldown", "&cYou need to wait %time% seconds before using this command again!");
                    cooldownMessage = cooldownMessage.replace("%time%", String.valueOf(secondsLeft));
                    player.sendMessage(formatColors(cooldownMessage));
                    return true;
                }
            }

            peeCooldowns.put(playerUUID, System.currentTimeMillis());

            String message = getConfig().getString("messages.pee-command", "&eYou peed!");
            player.sendMessage(formatColors(message));

            int duration = getConfig().getInt("items.pee.duration", 5);
            double speed = getConfig().getDouble("items.pee.velocity", 0.5);

            final long lifetime = Math.max(1L, getConfig().getLong("items.pee.lifetime", 20L));
            final int[] remainingTicks = {Math.max(1, duration * 20)};
            final int[] taskId = {-1};
            taskId[0] = getServer().getScheduler().scheduleSyncRepeatingTask(PooAndPee.this, () -> {
                if (remainingTicks[0] <= 0 || !player.isOnline()) {
                    getServer().getScheduler().cancelTask(taskId[0]);
                    return;
                }

                Location location = player.getLocation();
                Vector direction = location.getDirection().normalize();

                Location spawnLocation = location.clone();
                spawnLocation.add(0, 0.6, 0);
                spawnLocation.add(direction.clone().multiply(0.5));

                ItemStack itemStack = createItemStack(true);
                Item droppedItem = player.getWorld().dropItem(spawnLocation, itemStack);
                droppedItem.setVelocity(direction.clone().multiply(speed));

                getServer().getScheduler().runTaskLater(PooAndPee.this, droppedItem::remove, lifetime);

                remainingTicks[0] -= 2;
            }, 0L, 2L);

            return true;
        }
    }

    private class PooCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!pluginEnabled) {
                sender.sendMessage(ChatColor.RED + "This plugin is not properly configured. Please check the server logs.");
                return true;
            }

            if (!(sender instanceof Player)) {
                String message = getConfig().getString("messages.only-players", "&cOnly players can use this command!");
                sender.sendMessage(formatColors(message));
                return true;
            }

            Player player = (Player) sender;

            if (!hasPermission(player, "poo")) {
                String message = getConfig().getString("messages.no-permission", "&cYou don't have permission to use this command!");
                player.sendMessage(formatColors(message));
                return true;
            }

            UUID playerUUID = player.getUniqueId();

            int cooldownTime = getConfig().getInt("cooldowns.poo", 120);
            if (pooCooldowns.containsKey(playerUUID)) {
                long secondsLeft = ((pooCooldowns.get(playerUUID) / 1000) + cooldownTime) - (System.currentTimeMillis() / 1000);
                if (secondsLeft > 0) {
                    String cooldownMessage = getConfig().getString("messages.cooldown", "&cYou need to wait %time% seconds before using this command again!");
                    cooldownMessage = cooldownMessage.replace("%time%", String.valueOf(secondsLeft));
                    player.sendMessage(formatColors(cooldownMessage));
                    return true;
                }
            }

            pooCooldowns.put(playerUUID, System.currentTimeMillis());

            String message = getConfig().getString("messages.poo-command", "&eYou pooped!");
            player.sendMessage(formatColors(message));

            int duration = getConfig().getInt("items.poo.duration", 5);

            final long lifetime = Math.max(1L, getConfig().getLong("items.poo.lifetime", 20L));

            final int[] remainingTicks = {Math.max(1, duration * 20)};
            final int[] taskId = {-1};
            taskId[0] = getServer().getScheduler().scheduleSyncRepeatingTask(PooAndPee.this, () -> {
                if (remainingTicks[0] <= 0 || !player.isOnline()) {
                    getServer().getScheduler().cancelTask(taskId[0]);
                    return;
                }

                Location location = player.getLocation();
                Vector direction = location.getDirection().normalize();

                Location spawnLocation = location.clone();
                spawnLocation.add(0, 0.5, 0);

                Vector backDirection = direction.clone().multiply(-1);
                spawnLocation.add(backDirection.multiply(0.8));

                ItemStack itemStack = createItemStack(false);
                Item droppedItem = player.getWorld().dropItem(spawnLocation, itemStack);
                droppedItem.setVelocity(new Vector(0, -0.2, 0));

                getServer().getScheduler().runTaskLater(PooAndPee.this, droppedItem::remove, lifetime);

                remainingTicks[0] -= 4;
            }, 0L, 4L);

            return true;
        }
    }

    private class ReloadCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                if (!(sender instanceof Player) || hasPermission((Player) sender, "reload")) {
                    reloadPlugin();
                    if (pluginEnabled) {
                        String message = getConfig().getString("messages.reload-success", "&aPoo and Pee plugin reloaded successfully!");
                        sender.sendMessage(formatColors(message));
                    } else {
                        sender.sendMessage(ChatColor.RED + "Reload failed, see server console.");
                    }
                } else {
                    String message = getConfig().getString("messages.no-permission", "&cYou don't have permission to use this command!");
                    sender.sendMessage(formatColors(message));
                }
                return true;
            }

            sender.sendMessage(ChatColor.RED + "Usage: /" + label + " reload");
            return true;
        }
    }
}