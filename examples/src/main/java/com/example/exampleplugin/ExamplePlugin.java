package com.example.exampleplugin;

import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import me.mapacheee.extendedhorizons.api.ExtendedHorizonsAPI;
import me.mapacheee.extendedhorizons.api.event.FakeChunkBatchLoadEvent.ChunkCoordinate;
import me.mapacheee.extendedhorizons.api.event.FakeChunkLoadEvent;
import me.mapacheee.extendedhorizons.api.event.FakeChunkUnloadEvent;
import me.mapacheee.extendedhorizons.api.event.FakeChunkBatchLoadEvent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Set;

/**
 * Example plugin demonstrating how to use the ExtendedHorizons API.
 * 
 * This plugin shows:
 * - How to access the API
 * - How to use API methods
 * - How to listen to fake chunk events
 * - How to check if chunks are fake
 */
public class ExamplePlugin extends JavaPlugin implements Listener {

    private ExtendedHorizonsAPI api;

    @Override
    public void onEnable() {
        // Setup API connection
        if (!setupAPI()) {
            getLogger().severe("ExtendedHorizons not found! Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Register event listeners
        getServer().getPluginManager().registerEvents(this, this);

        getLogger().info("ExamplePlugin enabled! ExtendedHorizons API is ready.");

        // Display some API statistics
        displayAPIStats();
    }

    /**
     * Sets up the ExtendedHorizons API connection.
     * 
     * @return true if API was successfully loaded, false otherwise
     */
    private boolean setupAPI() {
        // Check if ExtendedHorizons is installed
        if (getServer().getPluginManager().getPlugin("ExtendedHorizons") == null) {
            getLogger().severe("ExtendedHorizons plugin not found!");
            return false;
        }

        try {
            // Get the API instance
            api = ExtendedHorizonsPlugin.getService(ExtendedHorizonsAPI.class);

            if (api == null) {
                getLogger().severe("Failed to get ExtendedHorizons API service!");
                return false;
            }

            getLogger().info("Successfully hooked into ExtendedHorizons API!");
            return true;

        } catch (Exception e) {
            getLogger().severe("Error while setting up ExtendedHorizons API: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Displays API statistics to console.
     */
    private void displayAPIStats() {
        int cacheSize = api.getCacheSize();
        double hitRate = api.getCacheHitRate();
        double memoryMB = api.getEstimatedMemoryUsageMB();

        getLogger().info("ExtendedHorizons Statistics:");
        getLogger().info("  Cache Size: " + cacheSize + " chunks");
        getLogger().info("  Hit Rate: " + String.format("%.2f", hitRate) + "%");
        getLogger().info("  Memory Usage: " + String.format("%.2f", memoryMB) + " MB");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by players!");
            return true;
        }

        if (command.getName().equalsIgnoreCase("fakechunkinfo")) {
            showFakeChunkInfo(player);
            return true;
        }

        if (command.getName().equalsIgnoreCase("clearfakechunks")) {
            api.clearFakeChunks(player);
            player.sendMessage("§aAll fake chunks have been cleared!");
            return true;
        }

        if (command.getName().equalsIgnoreCase("refreshfakechunks")) {
            api.refreshFakeChunks(player);
            player.sendMessage("§aFake chunks have been refreshed!");
            return true;
        }

        return false;
    }

    /**
     * Shows fake chunk information to a player.
     */
    private void showFakeChunkInfo(Player player) {
        // Get all fake chunks for this player
        Set<ChunkCoordinate> fakeChunks = api.getFakeChunksForPlayer(player);

        // Get count
        int count = api.getFakeChunkCount(player);

        // Check if player's current chunk is fake
        int playerChunkX = player.getLocation().getBlockX() >> 4;
        int playerChunkZ = player.getLocation().getBlockZ() >> 4;
        boolean currentChunkIsFake = api.isFakeChunk(player, playerChunkX, playerChunkZ);

        // Check if fake chunks are enabled in this world
        boolean worldEnabled = api.isFakeChunksEnabledForWorld(player.getWorld().getName());

        // Display information
        player.sendMessage("§6=== Fake Chunk Info ===");
        player.sendMessage("§eTotal fake chunks: §f" + count);
        player.sendMessage("§eWorld enabled: §f" + (worldEnabled ? "Yes" : "No"));
        player.sendMessage("§eCurrent chunk is fake: §f" + (currentChunkIsFake ? "Yes" : "No"));
        player.sendMessage("§eCurrent position: §f" + playerChunkX + ", " + playerChunkZ);

        if (!fakeChunks.isEmpty() && fakeChunks.size() <= 10) {
            player.sendMessage("§eFake chunks (showing first 10):");
            int shown = 0;
            for (ChunkCoordinate coord : fakeChunks) {
                if (shown++ >= 10)
                    break;
                player.sendMessage("  §7- " + coord.x() + ", " + coord.z());
            }
        }
    }

    // ==================== Event Listeners ====================

    /**
     * Called when a fake chunk is about to be loaded for a player.
     * This event is cancellable.
     */
    @EventHandler
    public void onFakeChunkLoad(FakeChunkLoadEvent event) {
        Player player = event.getPlayer();
        int chunkX = event.getChunkX();
        int chunkZ = event.getChunkZ();
        FakeChunkLoadEvent.LoadSource source = event.getLoadSource();

        // Example: Cancel loading chunks beyond certain coordinates
        if (Math.abs(chunkX) > 10000 || Math.abs(chunkZ) > 10000) {
            event.setCancelled(true);
            getLogger().info("Cancelled fake chunk load at " + chunkX + "," + chunkZ
                    + " for " + player.getName() + " (too far)");
            return;
        }

        // Example: Log when chunks are generated (slowest load source)
        if (source == FakeChunkLoadEvent.LoadSource.GENERATED) {
            getLogger().info("Generated new fake chunk at " + chunkX + "," + chunkZ
                    + " for " + player.getName());
        }

        // Example: Notify player about cache performance
        if (source == FakeChunkLoadEvent.LoadSource.MEMORY_CACHE) {
            // Fast load from cache - good performance
            getLogger().fine("Fast cache hit for chunk " + chunkX + "," + chunkZ);
        }
    }

    /**
     * Called when a fake chunk is unloaded from a player.
     * This event is not cancellable.
     */
    @EventHandler
    public void onFakeChunkUnload(FakeChunkUnloadEvent event) {
        Player player = event.getPlayer();
        int chunkX = event.getChunkX();
        int chunkZ = event.getChunkZ();
        FakeChunkUnloadEvent.UnloadReason reason = event.getReason();

        // Example: Track why chunks are being unloaded
        switch (reason) {
            case DISTANCE:
                // Player moved too far away - normal behavior
                break;

            case WORLD_CHANGE:
                getLogger().info(player.getName() + " changed worlds, unloading fake chunks");
                break;

            case QUIT:
                getLogger().info(player.getName() + " quit, unloading fake chunks");
                break;

            case MANUAL:
                getLogger().info("Manually cleared fake chunks for " + player.getName());
                break;
        }
    }

    /**
     * Called when multiple fake chunks are loaded at once.
     * This event is not cancellable.
     */
    @EventHandler
    public void onFakeChunkBatchLoad(FakeChunkBatchLoadEvent event) {
        Player player = event.getPlayer();
        Set<ChunkCoordinate> chunks = event.getChunks();

        // Example: Notify player when a large batch is loaded
        if (chunks.size() > 50) {
            player.sendMessage("§7Loading " + chunks.size() + " fake chunks...");
        }

        getLogger().info("Loaded batch of " + chunks.size() + " fake chunks for "
                + player.getName());
    }
}
