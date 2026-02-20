package me.mapacheee.extendedhorizons.api.event;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Called when a fake chunk is about to be sent to a player.
 * This event is cancellable - if cancelled, the chunk will not be sent.
 */
public class FakeChunkLoadEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();
    private boolean cancelled = false;

    private final Player player;
    private final int chunkX;
    private final int chunkZ;
    private final World world;
    private final LoadSource loadSource;

    /**
     * Creates a new FakeChunkLoadEvent.
     *
     * @param player     The player receiving the fake chunk
     * @param chunkX     The X coordinate of the chunk
     * @param chunkZ     The Z coordinate of the chunk
     * @param world      The world containing the chunk
     * @param loadSource Where the chunk data is coming from
     */
    public FakeChunkLoadEvent(@NotNull Player player, int chunkX, int chunkZ,
            @NotNull World world, @NotNull LoadSource loadSource) {
        this.player = player;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.world = world;
        this.loadSource = loadSource;
    }

    /**
     * Gets the player receiving the fake chunk.
     *
     * @return The player
     */
    @NotNull
    public Player getPlayer() {
        return player;
    }

    /**
     * Gets the X coordinate of the chunk.
     *
     * @return Chunk X coordinate
     */
    public int getChunkX() {
        return chunkX;
    }

    /**
     * Gets the Z coordinate of the chunk.
     *
     * @return Chunk Z coordinate
     */
    public int getChunkZ() {
        return chunkZ;
    }

    /**
     * Gets the world containing the chunk.
     *
     * @return The world
     */
    @NotNull
    public World getWorld() {
        return world;
    }

    /**
     * Gets the source from which the chunk is being loaded.
     *
     * @return The load source
     */
    @NotNull
    public LoadSource getLoadSource() {
        return loadSource;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @NotNull
    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    @NotNull
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    /**
     * Represents the source from which a fake chunk is loaded.
     */
    public enum LoadSource {
        /**
         * Chunk loaded from PacketEvents cache (fastest).
         */
        PACKET_CACHE,

        /**
         * Chunk loaded from server memory cache (fast).
         */
        MEMORY_CACHE,

        /**
         * Chunk loaded from disk without generation (fast).
         */
        DISK,

        /**
         * Chunk was generated because it didn't exist (slowest).
         */
        GENERATED
    }
}
