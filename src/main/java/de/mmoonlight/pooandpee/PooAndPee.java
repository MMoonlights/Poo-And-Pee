package de.mmoonlight.pooandpee;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

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
    private boolean pluginEnabled = true;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        
        String version = getServer().getBukkitVersion();
        
        isLegacyVersion = version.contains("1.7") || 
                          version.contains("1.8") || 
                          version.contains("1.9") || 
                          version.contains("1.10") || 
                          version.contains("1.11") || 
                          version.contains("1.12");
        
        supportsHexColors = !isLegacyVersion && (
                          version.contains("1.16") || 
                          version.contains("1.17") || 
                          version.contains("1.18") || 
                          version.contains("1.19") || 
                          version.contains("1.20"));
        
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
        
        getCommand("pee").setExecutor(new PeeCommand());
        getCommand("poo").setExecutor(new PooCommand());
    }
    
    private boolean configContainsHexColors() {
        String peeCommand = getConfig().getString("messages.pee-command", "");
        String pooCommand = getConfig().getString("messages.poo-command", "");
        String onlyPlayers = getConfig().getString("messages.only-players", "");
        String cooldown = getConfig().getString("messages.cooldown", "");
        
        return HEX_PATTERN.matcher(peeCommand).find() || 
               HEX_PATTERN.matcher(pooCommand).find() || 
               HEX_PATTERN.matcher(onlyPlayers).find() || 
               HEX_PATTERN.matcher(cooldown).find();
    }
    
    private boolean setupMaterials() {
        String peeMaterialName = getConfig().getString("items.pee.material");
        String pooMaterialName = getConfig().getString("items.poo.material");
        
        if (peeMaterialName == null || peeMaterialName.isEmpty()) {
            getLogger().severe("Pee material is not specified in config!");
            getLogger().severe("Please add a valid material name to items.pee.material in config.yml");
            return false;
        }
        
        if (pooMaterialName == null || pooMaterialName.isEmpty()) {
            getLogger().severe("Poo material is not specified in config!");
            getLogger().severe("Please add a valid material name to items.poo.material in config.yml");
            return false;
        }
        
        try {
            peeMaterial = Material.valueOf(peeMaterialName.toUpperCase());
        } catch (Exception e) {
            getLogger().severe("Invalid material name in config: " + peeMaterialName);
            getLogger().severe("Material '" + peeMaterialName + "' does not exist in '" + getServer().getBukkitVersion() + "'");
            return false;
        }
        
        try {
            pooMaterial = Material.valueOf(pooMaterialName.toUpperCase());
            getLogger().info("Successfully set poo material to: " + pooMaterialName);
        } catch (Exception e) {
            getLogger().severe("Invalid material name in config: " + pooMaterialName);
            getLogger().severe("Material '" + pooMaterialName + "' does not exist in '" + getServer().getBukkitVersion() + "'");
            return false;
        }
        
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
                    matcher.appendReplacement(buffer, net.md_5.bungee.api.ChatColor.of("#" + hexColor).toString());
                }
                matcher.appendTail(buffer);
                
                return ChatColor.translateAlternateColorCodes('&', buffer.toString());
            } catch (Exception e) {
                getLogger().warning("Error processing hex color: " + e.getMessage());
                return ChatColor.translateAlternateColorCodes('&', text);
            }
        } else {
            if (HEX_PATTERN.matcher(text).find()) {
                getLogger().warning("Attempted to use hex colors on a server that doesn't support them!");
                getLogger().warning("Message containing hex colors: " + text);
                return ChatColor.RED + "Error: Hex colors not supported on this server version!";
            }
            return ChatColor.translateAlternateColorCodes('&', text);
        }
    }
    
    private ItemStack createItemStack(boolean isPee) {
        try {
            return new ItemStack(isPee ? peeMaterial : pooMaterial);
        } catch (Exception e) {
            getLogger().severe("Failed to create item: " + e.getMessage());
            return new ItemStack(Material.STONE);
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
            
            String message = getConfig().getString("messages.pee-command");
            player.sendMessage(formatColors(message));
            
            int duration = getConfig().getInt("items.pee.duration", 5);
            double speed = getConfig().getDouble("items.pee.velocity", 0.5);
            
            final int[] remainingTicks = {duration * 20};
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
                
                getServer().getScheduler().runTaskLater(PooAndPee.this, droppedItem::remove, 20L);
                
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
            
            String message = getConfig().getString("messages.poo-command");
            player.sendMessage(formatColors(message));
            
            int duration = getConfig().getInt("items.poo.duration", 5);
            
            final int[] remainingTicks = {duration * 20};
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
                
                getServer().getScheduler().runTaskLater(PooAndPee.this, droppedItem::remove, 20L);
                
                remainingTicks[0] -= 4;
            }, 0L, 4L);
            
            return true;
        }
    }
}