package me.mapacheee.extendedhorizons.fakechunks.backend;

import com.thewinterframework.service.annotation.Service;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;

import java.util.concurrent.CompletableFuture;

@Service
public final class PaperChunkBackend implements ChunkBackend {

    private static final byte LEVEL_CHUNK_WITH_LIGHT_PACKET_ID = 0x2C;

    @Override
    public CompletableFuture<ByteBuf> buildChunkPayload(
            World world,
            int chunkX,
            int chunkZ,
            boolean generateMissingChunks,
            ChunkScheduler scheduler
    ) {
        if (world == null || scheduler == null) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<ByteBuf> future = new CompletableFuture<>();
        Runnable task = () -> {
            try {
                ServerLevel level = ((CraftWorld) world).getHandle();
                LevelChunk levelChunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (levelChunk == null) {
                    future.complete(null);
                    return;
                }
                ByteBuf packetData = this.serializeLevelChunkWithLight(
                        level,
                        levelChunk,
                        chunkX,
                        chunkZ
                );
                future.complete(packetData);
            } catch (Throwable throwable) {
                future.complete(null);
            }
        };

        if (generateMissingChunks) {
            world.getChunkAtAsync(chunkX, chunkZ, true)
                    .thenRun(() -> {
                        boolean accepted = scheduler.runAtChunk(world, chunkX, chunkZ, task);
                        if (!accepted) {
                            future.complete(null);
                        }
                    })
                    .exceptionally(throwable -> {
                        future.complete(null);
                        return null;
                    });
        } else {
            boolean accepted = scheduler.runAtChunk(world, chunkX, chunkZ, task);
            if (!accepted) {
                future.complete(null);
            }
        }

        return future;
    }

    private ByteBuf serializeLevelChunkWithLight(ServerLevel level, LevelChunk chunk, int chunkX, int chunkZ) {
        ByteBuf raw = PooledByteBufAllocator.DEFAULT.buffer();
        FriendlyByteBuf buf = new FriendlyByteBuf(raw);
        try {
            VarInt.write(buf, LEVEL_CHUNK_WITH_LIGHT_PACKET_ID);
            buf.writeInt(chunkX);
            buf.writeInt(chunkZ);

            ClientboundLevelChunkPacketData chunkData = new ClientboundLevelChunkPacketData(chunk);
            RegistryFriendlyByteBuf registryBuf = new RegistryFriendlyByteBuf(raw, level.registryAccess());
            chunkData.write(registryBuf);

            ClientboundLightUpdatePacketData lightData = new ClientboundLightUpdatePacketData(
                    new ChunkPos(chunkX, chunkZ),
                    level.getLightEngine(),
                    null,
                    null
            );
            lightData.write(buf);
            return raw;
        } catch (Throwable throwable) {
            raw.release();
            return null;
        }
    }
}

