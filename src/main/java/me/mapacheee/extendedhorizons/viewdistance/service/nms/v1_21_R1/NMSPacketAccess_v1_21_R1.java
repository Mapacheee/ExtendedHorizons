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
import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import java.util.BitSet;
import me.mapacheee.extendedhorizons.shared.service.ConfigService;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import io.netty.buffer.Unpooled;

@Service
public class NMSPacketAccess_v1_21_R1 implements NMSPacketAccess {

    private final ConfigService configService;
    private static java.lang.reflect.Method writeMethod;
        this.configService = configService;
    }

    private static java.lang.reflect.Method writeMethod;
    private static java.lang.reflect.Constructor<ClientboundLevelChunkWithLightPacket> chunkPacketConstructor;

    static {
        try {
            writeMethod = ClientboundLevelChunkWithLightPacket.class.getDeclaredMethod("write",
                    RegistryFriendlyByteBuf.class);
            writeMethod.setAccessible(true);

            chunkPacketConstructor = ClientboundLevelChunkWithLightPacket.class
                    .getDeclaredConstructor(RegistryFriendlyByteBuf.class);
            chunkPacketConstructor.setAccessible(true);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public byte[] serializeChunkPacket(Object packetObj) {
        if (!(packetObj instanceof ClientboundLevelChunkWithLightPacket)) {
            return null;
        }
        ClientboundLevelChunkWithLightPacket packet = (ClientboundLevelChunkWithLightPacket) packetObj;

        var buffer = Unpooled.buffer();
        try {
            var registryAccess = MinecraftServer.getServer().registryAccess();
            var buf = new RegistryFriendlyByteBuf(buffer, registryAccess);

            if (writeMethod != null) {
                writeMethod.invoke(packet, buf);
            }

            byte[] data = new byte[buffer.readableBytes()];
            buffer.readBytes(data);
            return data;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        } finally {
            buffer.release();
        }
    }

    @Override
    public Object deserializeChunkPacket(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }

        var buffer = Unpooled.wrappedBuffer(data);
        try {
            var registryAccess = MinecraftServer.getServer().registryAccess();
            var buf = new RegistryFriendlyByteBuf(buffer, registryAccess);

            if (chunkPacketConstructor != null) {
                return chunkPacketConstructor.newInstance(buf);
            }
            return null;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        } finally {
            buffer.release();
        }
    }

    @Override
    public Object createChunkPacket(Object chunk) {
        if (!(chunk instanceof LevelChunk))
            return null;

        try {
            LevelChunk nmsChunk = (LevelChunk) chunk;
            LevelLightEngine lightEngine = nmsChunk.getLevel().getLightEngine();
            BitSet[] lightMasks = getLightMasks(nmsChunk);

            boolean enableAntiXray = configService.get().performance().fakeChunks().enableAntiXray();
            boolean trustEdges = !enableAntiXray;

            ClientboundLevelChunkWithLightPacket packet = new ClientboundLevelChunkWithLightPacket(
                    nmsChunk,
                    lightEngine,
                    lightMasks[0],
                    lightMasks[1],
                    trustEdges);

            return packet;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public Object createSurfaceOnlyChunkPacket(Object chunk, int depthBelowSurface) {
        if (!(chunk instanceof LevelChunk))
            return null;

        try {
            LevelChunk nmsChunk = (LevelChunk) chunk;
            LevelLightEngine lightEngine = nmsChunk.getLevel().getLightEngine();

            BitSet[] lightMasks = getSurfaceOnlyLightMasks(nmsChunk, depthBelowSurface);

            ClientboundLevelChunkWithLightPacket packet = new ClientboundLevelChunkWithLightPacket(
                    nmsChunk,
                    lightEngine,
                    lightMasks[0],
                    lightMasks[1],
                    false);

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

    /**
     * Creates light masks based on heightmap, only including surface sections.
     * This dramatically reduces packet size by excluding underground sections.
     */
    private BitSet[] getSurfaceOnlyLightMasks(LevelChunk chunk, int depthBelowSurface) {
        int sectionCount = chunk.getSections().length;
        BitSet skyLight = new BitSet(sectionCount + 2);
        BitSet blockLight = new BitSet(sectionCount + 2);

        int minY = -64;
        int sectionHeight = 16;

        var heightmap = chunk
                .getOrCreateHeightmapUnprimed(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING);

        int maxHeight = Integer.MIN_VALUE;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int height = heightmap.getFirstAvailable(x, z);
                if (height > maxHeight) {
                    maxHeight = height;
                }
            }
        }

        int minIncludeY = maxHeight - depthBelowSurface;
        int maxIncludeY = maxHeight + sectionHeight;

        LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < sectionCount; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir()) {
                continue;
            }

            int sectionY = minY + (i * sectionHeight);
            int sectionMaxY = sectionY + sectionHeight;

            if (sectionMaxY >= minIncludeY && sectionY <= maxIncludeY) {
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
            int estimated = Math.max(1, configService.get().bandwidthSaver().estimatedPacketSize());
            try {
                if (configService.get().performance().fakeChunks().diskCache()) {
                    byte[] data = serializeChunkPacket(packet);
                    if (data != null && data.length > 0) {
                        return data.length;
                    }
                }
            } catch (Throwable ignored) {
            }
            return estimated;
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
