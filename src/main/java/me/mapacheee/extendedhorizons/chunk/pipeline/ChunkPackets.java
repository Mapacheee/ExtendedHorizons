package me.mapacheee.extendedhorizons.chunk.pipeline;

import net.minecraft.network.protocol.Packet;

public record ChunkPackets(Packet<?> chunkPacket, Packet<?> lightPacket) {}