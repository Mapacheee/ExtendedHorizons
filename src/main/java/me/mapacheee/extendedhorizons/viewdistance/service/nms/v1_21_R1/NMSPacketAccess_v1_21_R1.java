package me.mapacheee.extendedhorizons.viewdistance.service.nms.v1_21_R1;

import me.mapacheee.extendedhorizons.viewdistance.service.nms.NMSPacketAccess;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetSimulationDistancePacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.network.protocol.Packet;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import com.thewinterframework.service.annotation.Service;
import java.util.BitSet;

@Service
public class NMSPacketAccess_v1_21_R1 implements NMSPacketAccess {

    @Override
    public Object createChunkPacket(Object chunk) {
        if (!(chunk instanceof LevelChunk))
            return null;

        try {
            LevelChunk nmsChunk = (LevelChunk) chunk;
            LevelLightEngine lightEngine = nmsChunk.getLevel().getLightEngine();
            BitSet[] lightMasks = getLightMasks(nmsChunk);

            ClientboundLevelChunkWithLightPacket packet = new ClientboundLevelChunkWithLightPacket(
                    nmsChunk,
                    lightEngine,
                    lightMasks[0],
                    lightMasks[1],
                    true);

            return packet;
        } catch (Exception e) {
            return null;
        }
    }

    private BitSet[] getLightMasks(LevelChunk chunk) {
        int sectionCount = chunk.getSections().length;
        BitSet skyLight = new BitSet(sectionCount + 2);
        BitSet blockLight = new BitSet(sectionCount + 2);

        LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < sectionCount; i++) {
            LevelChunkSection section = sections[i];
            if (section != null && !section.hasOnlyAir()) {
                skyLight.set(i + 1);
                blockLight.set(i + 1);
            }
        }
        return new BitSet[] { skyLight, blockLight };
    }

    @Override
    public Object createUnloadPacket(int x, int z) {
        return new ClientboundForgetLevelChunkPacket(new ChunkPos(x, z));
    }

    @Override
    public void sendPacket(Player player, Object packet) {
        if (packet instanceof Packet) {
            var connection = ((CraftPlayer) player).getHandle().connection;
            if (connection != null) {
                connection.send((Packet<?>) packet);
            }
        }
    }

    @Override
    public int getPacketSize(Object packet) {
        if (packet instanceof ClientboundLevelChunkWithLightPacket) {
            return -1;
        }
        return 512;
    }

    @Override
    public Object createChunkCacheRadiusPacket(int radius) {
        return new ClientboundSetChunkCacheRadiusPacket(radius);
    }

    @Override
    public Object createChunkCacheCenterPacket(int x, int z) {
        return new ClientboundSetChunkCacheCenterPacket(x, z);
    }

    @Override
    public Object createSimulationDistancePacket(int distance) {
        return new ClientboundSetSimulationDistancePacket(distance);
    }

}
