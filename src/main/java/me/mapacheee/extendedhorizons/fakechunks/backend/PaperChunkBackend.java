package me.mapacheee.extendedhorizons.fakechunks.backend;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import me.mapacheee.extendedhorizons.fakechunks.antixray.AntiXrayProcessor;
import me.mapacheee.extendedhorizons.fakechunks.antixray.AntiXrayService;
import me.mapacheee.extendedhorizons.fakechunks.antixray.VarIntUtil;
import me.mapacheee.extendedhorizons.fakechunks.dispatch.GlobalGenerationLimiterService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.util.Util;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

@Service
public final class PaperChunkBackend implements ChunkBackend {

    private static final byte LEVEL_CHUNK_WITH_LIGHT_PACKET_ID = 0x2C;
    private static final Heightmap.Types[] SENDABLE_HEIGHTMAP_TYPES = Arrays.stream(Heightmap.Types.values())
        .filter(Heightmap.Types::sendToClient)
        .toArray(Heightmap.Types[]::new);
    private static final int[] SENDABLE_HEIGHTMAP_TYPE_IDS = Arrays.stream(SENDABLE_HEIGHTMAP_TYPES)
        .mapToInt(Enum::ordinal)
        .toArray();
    private static final MethodHandle GET_NON_EMPTY_BLOCK_COUNT = Util.make(() -> {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(LevelChunkSection.class, MethodHandles.lookup());
            return lookup.findGetter(LevelChunkSection.class, "nonEmptyBlockCount", short.class)
                .asType(MethodType.methodType(short.class, LevelChunkSection.class));
        } catch (ReflectiveOperationException exception) {
            throw new RuntimeException("Unable to access nonEmptyBlockCount", exception);
        }
    });

    private final AntiXrayService antiXrayService;
    private final GlobalGenerationLimiterService generationLimiterService;

    @Inject
    public PaperChunkBackend(
        AntiXrayService antiXrayService,
        GlobalGenerationLimiterService generationLimiterService
    ) {
        this.antiXrayService = antiXrayService;
        this.generationLimiterService = generationLimiterService;
    }

    @Override
    public CompletableFuture<ByteBuf> buildChunkPayload(
        World world,
        int chunkX,
        int chunkZ,
        boolean generateMissingChunks,
        ChunkScheduler scheduler) {
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
                AntiXrayProcessor antiXrayProcessor = this.antiXrayService.resolve(world);
                ByteBuf packetData = this.serializeLevelChunkWithLight(
                    level,
                    levelChunk,
                    chunkX,
                    chunkZ,
                    antiXrayProcessor);
                future.complete(packetData);
            } catch (Throwable throwable) {
                future.complete(null);
            }
        };

        if (generateMissingChunks) {
            if (!world.isChunkLoaded(chunkX, chunkZ) && !this.generationLimiterService.tryAcquire()) {
                future.completeExceptionally(new IllegalStateException("Global generation budget exhausted"));
                return future;
            }
            world.getChunkAtAsync(chunkX, chunkZ, true)
                .thenRun(task)
                .exceptionally(throwable -> {
                    future.complete(null);
                    return null;
                });
        } else {
            world.getChunkAtAsync(chunkX, chunkZ, false)
                .thenAccept((chunk) -> {
                    if (chunk == null) {
                        future.complete(null);
                        return;
                    }
                    task.run();
                })
                .exceptionally(throwable -> {
                    future.complete(null);
                    return null;
                });
        }

        return future;
    }

    @SuppressWarnings("deprecation")
    private ByteBuf serializeLevelChunkWithLight(
        ServerLevel level,
        LevelChunk chunk,
        int chunkX,
        int chunkZ,
        AntiXrayProcessor antiXrayProcessor
    ) {
        ByteBuf raw = PooledByteBufAllocator.DEFAULT.buffer();
        FriendlyByteBuf buf = new FriendlyByteBuf(raw);
        try {
            VarInt.write(buf, LEVEL_CHUNK_WITH_LIGHT_PACKET_ID);
            buf.writeInt(chunkX);
            buf.writeInt(chunkZ);

            if (antiXrayProcessor == null) {
                ClientboundLevelChunkPacketData chunkData = new ClientboundLevelChunkPacketData(chunk);
                RegistryFriendlyByteBuf registryBuf = new RegistryFriendlyByteBuf(raw, level.registryAccess());
                chunkData.write(registryBuf);
            } else {
                this.writeChunkDataWithAntiXray(buf, chunk, antiXrayProcessor);
            }

            ClientboundLightUpdatePacketData lightData = new ClientboundLightUpdatePacketData(
                new ChunkPos(chunkX, chunkZ),
                level.getLightEngine(),
                null,
                null);
            lightData.write(buf);
            return raw;
        } catch (Throwable throwable) {
            raw.release();
            return null;
        }
    }

    private void writeChunkDataWithAntiXray(FriendlyByteBuf out, LevelChunk chunk, AntiXrayProcessor antiXrayProcessor) {
        writeHeightmaps(out, chunk);

        ByteBuf sectionBuffer = PooledByteBufAllocator.DEFAULT.buffer();
        try {
            FriendlyByteBuf sectionBuf = new FriendlyByteBuf(sectionBuffer);
            int minSectionY = chunk.getMinSectionY();
            LevelChunkSection[] sections = chunk.getSections();
            for (int i = 0; i < sections.length; i++) {
                writeSection(sectionBuf, sections[i], antiXrayProcessor, i + minSectionY);
            }
            VarIntUtil.writeVarInt(out, sectionBuffer.readableBytes());
            out.writeBytes(sectionBuffer, sectionBuffer.readerIndex(), sectionBuffer.readableBytes());
        } finally {
            sectionBuffer.release();
        }

        VarIntUtil.writeVarInt(out, 0);
    }

    private static void writeHeightmaps(FriendlyByteBuf out, LevelChunk chunk) {
        int[] presentIds = new int[SENDABLE_HEIGHTMAP_TYPES.length];
        long[][] presentData = new long[SENDABLE_HEIGHTMAP_TYPES.length][];
        int presentCount = 0;
        for (int i = 0; i < SENDABLE_HEIGHTMAP_TYPES.length; i++) {
            Heightmap.Types type = SENDABLE_HEIGHTMAP_TYPES[i];
            if (!chunk.hasPrimedHeightmap(type)) {
                continue;
            }
            presentIds[presentCount] = SENDABLE_HEIGHTMAP_TYPE_IDS[i];
            presentData[presentCount] = chunk.getOrCreateHeightmapUnprimed(type).getRawData();
            presentCount++;
        }

        VarIntUtil.writeVarInt(out, presentCount);
        for (int i = 0; i < presentCount; i++) {
            VarIntUtil.writeVarInt(out, presentIds[i]);
            FriendlyByteBuf.writeLongArray(out, presentData[i]);
        }
    }

    private static void writeSection(
        FriendlyByteBuf out,
        LevelChunkSection section,
        AntiXrayProcessor antiXrayProcessor,
        int sectionY
    ) {
        out.writeShort(getNonEmptyBlockCount(section));

        int preReaderIndex = out.readerIndex();
        int preWriterIndex = out.writerIndex();
        section.getStates().write(out, null, 0);

        out.readerIndex(preWriterIndex);
        antiXrayProcessor.process(out, sectionY, false);
        out.readerIndex(preReaderIndex);

        section.getBiomes().write(out, null, 0);
    }

    private static short getNonEmptyBlockCount(LevelChunkSection section) {
        try {
            return (short) GET_NON_EMPTY_BLOCK_COUNT.invokeExact(section);
        } catch (Throwable throwable) {
            throw new IllegalStateException("Failed to read non-empty block count", throwable);
        }
    }
}
