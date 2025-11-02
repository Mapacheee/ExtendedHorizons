package me.mapacheee.extendedhorizons.viewdistance.entity;

import org.bukkit.Chunk;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;


/*
 * Represents the view-related state of a connected player.
 * This object holds information about the player's current and target
 * view distances, and serves as a central point for managing their view.
 */
public class PlayerView {

    private final UUID uuid;
    private int targetDistance;
    private final Set<Long> sentChunks = Collections.newSetFromMap(new ConcurrentHashMap<>());


    public PlayerView(Player player, int initialDistance) {
        this.uuid = player.getUniqueId();
        this.targetDistance = initialDistance;
    }

    public UUID getUuid() {
        return uuid;
    }

    public int getTargetDistance() {
        return targetDistance;
    }

    public void setTargetDistance(int targetDistance) {
        this.targetDistance = targetDistance;
    }

    public Set<Long> getSentChunks() {
        return sentChunks;
    }

    public static long getChunkKey(Chunk chunk) {
        return getChunkKey(chunk.getX(), chunk.getZ());
    }

    public static long getChunkKey(int x, int z) {
        return (long) x & 0xFFFFFFFFL | ((long) z & 0xFFFFFFFFL) << 32;
    }
}
