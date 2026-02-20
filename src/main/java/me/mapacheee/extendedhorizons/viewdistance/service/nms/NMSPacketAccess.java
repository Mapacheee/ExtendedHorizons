package me.mapacheee.extendedhorizons.viewdistance.service.nms;

import org.bukkit.entity.Player;

public interface NMSPacketAccess {
    /**
     * Creates a ClientboundLevelChunkWithLightPacket from a chunk.
     * 
     * @return The NMS packet object.
     */
    Object createChunkPacket(Object chunk);

    /**
     * Creates a ClientboundForgetLevelChunkPacket.
     * 
     * @return The NMS packet object.
     */
    Object createUnloadPacket(int x, int z);

    /**
     * Sends a packet to a player.
     * 
     * @param packet The NMS packet object (or Bukkit Packet if supported).
     */
    void sendPacket(Player player, Object packet);

    /**
     * Gets the estimated size of a packet in bytes.
     */
    int getPacketSize(Object packet);

    /**
     * Creates a ClientboundSetChunkCacheRadiusPacket.
     * 
     * @param radius The radius.
     * @return The NMS packet object.
     */
    Object createChunkCacheRadiusPacket(int radius);

    /**
     * Creates a ClientboundSetChunkCacheCenterPacket.
     * 
     * @param x The chunk X.
     * @param z The chunk Z.
     * @return The NMS packet object.
     */
    Object createChunkCacheCenterPacket(int x, int z);

    /**
     * Creates a ClientboundSetSimulationDistancePacket.
     * 
     * @param distance The simulation distance.
     * @return The NMS packet object.
     */
    Object createSimulationDistancePacket(int distance);
}
