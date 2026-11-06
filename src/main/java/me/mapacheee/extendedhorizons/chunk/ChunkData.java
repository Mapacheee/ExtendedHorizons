package me.mapacheee.extendedhorizons.chunk;

import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;

public record ChunkData(ClientboundLevelChunkWithLightPacket packet, int bytes) {}
