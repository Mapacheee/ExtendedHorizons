package me.mapacheee.extendedhorizons.integration.service;

import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import org.bukkit.Chunk;
import org.bukkit.entity.Player;

/**
 * Interface for PacketEvents Service - Handles all PacketEvents integration for chunk management
 * Manages packet listeners and optimized chunk sending using PacketEvents
 */
public interface IPacketEventsService {

    void sendChunkPacket(Player player, WrapperPlayServerChunkData chunkData);

    void sendFakeChunkPacket(Player player, int chunkX, int chunkZ);

    void sendUnloadChunkPacket(Player player, int chunkX, int chunkZ);

    WrapperPlayServerChunkData createChunkData(Chunk chunk);

    WrapperPlayServerChunkData createFakeChunkData(int chunkX, int chunkZ);

    void unregisterListener();
}
