package me.mapacheee.extendedhorizons.fakechunks.backend;

import com.google.inject.Inject;
import com.thewinterframework.configurate.Container;
import com.thewinterframework.service.annotation.Service;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import me.mapacheee.extendedhorizons.config.EhConfig;
import me.mapacheee.extendedhorizons.fakechunks.antixray.AntiXrayProcessor;
import me.mapacheee.extendedhorizons.fakechunks.antixray.AntiXrayService;
import me.mapacheee.extendedhorizons.fakechunks.antixray.VarIntUtil;
import me.mapacheee.extendedhorizons.fakechunks.cache.LightPayloadCacheService;
import me.mapacheee.extendedhorizons.fakechunks.dispatch.GlobalGenerationLimiterService;
import me.mapacheee.extendedhorizons.runtime.ChunkBuildMetricsService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftChunk;
import org.bukkit.craftbukkit.CraftWorld;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;
import java.util.UUID;
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
    private static final MethodHandle GET_NON_EMPTY_BLOCK_COUNT = createNonEmptyBlockCountHandle();

    private static MethodHandle createNonEmptyBlockCountHandle() {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(LevelChunkSection.class, MethodHandles.lookup());
            return lookup.findGetter(LevelChunkSection.class, "nonEmptyBlockCount", short.class)
                .asType(MethodType.methodType(short.class, LevelChunkSection.class));
        } catch (ReflectiveOperationException exception) {
            throw new RuntimeException("Unable to access nonEmptyBlockCount", exception);
        }
    }

    private final AntiXrayService antiXrayService;
    private final ChunkSerializationExecutorService serializationExecutorService;
    private final LightPayloadCacheService lightPayloadCacheService;
    private final GlobalGenerationLimiterService generationLimiterService;
    private final ChunkBuildMetricsService metricsService;
    private final Container<EhConfig> configContainer;

    @Inject
    public PaperChunkBackend(
        Container<EhConfig> configContainer,
        AntiXrayService antiXrayService,
        ChunkSerializationExecutorService serializationExecutorService,
        LightPayloadCacheService lightPayloadCacheService,
        GlobalGenerationLimiterService generationLimiterService,
        ChunkBuildMetricsService metricsService
    ) {
        this.configContainer = configContainer;
        this.antiXrayService = antiXrayService;
        this.serializationExecutorService = serializationExecutorService;
        this.lightPayloadCacheService = lightPayloadCacheService;
        this.generationLimiterService = generationLimiterService;
        this.metricsService = metricsService;
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
        java.util.function.Consumer<Chunk> task = (asyncChunk) -> {
            try {
                ServerLevel level = ((CraftWorld) world).getHandle();
                LevelChunk resolvedChunk = this.resolveLevelChunk(asyncChunk);
                if (resolvedChunk == null) {
                    future.complete(null);
                    return;
                }
                AntiXrayProcessor antiXrayProcessor = this.antiXrayService.resolve(world);
                UUID worldId = world.getUID();
                long chunkKey = ChunkPos.asLong(chunkX, chunkZ);

                boolean offloadSerialization = this.configContainer.get().serializationWorkers() > 0
                    && antiXrayProcessor == null;
                CompletableFuture<ByteBuf> serializationFuture;
                if (antiXrayProcessor != null && this.configContainer.get().serializationWorkers() > 0) {
                    long snapshotStart = System.nanoTime();
                    AntiXrayChunkSnapshot snapshot = this.captureAntiXraySnapshot(resolvedChunk, antiXrayProcessor, worldId, chunkKey);
                    this.metricsService.recordAntiXraySnapshot(System.nanoTime() - snapshotStart);
                    if (snapshot == null) {
                        future.complete(null);
                        return;
                    }
                    try {
                        serializationFuture = this.serializationExecutorService
                            .submit(() -> {
                                long asyncStart = System.nanoTime();
                                ByteBuf payload = this.serializeAntiXraySnapshot(chunkX, chunkZ, snapshot);
                                this.metricsService.recordAntiXrayAsync(System.nanoTime() - asyncStart);
                                if (payload == null) {
                                    this.metricsService.recordAntiXrayFallback();
                                }
                                return payload;
                            });
                    } catch (Throwable throwable) {
                        this.metricsService.recordAntiXrayFallback();
                        snapshot.release();
                        ByteBuf packetData = this.serializeLevelChunkWithLight(
                            level,
                            resolvedChunk,
                            chunkX,
                            chunkZ,
                            worldId,
                            chunkKey,
                            antiXrayProcessor);
                        serializationFuture = CompletableFuture.completedFuture(packetData);
                    }
                } else if (offloadSerialization) {
                    try {
                        serializationFuture = this.serializationExecutorService.submit(() -> this.serializeLevelChunkWithLight(
                            level,
                            resolvedChunk,
                            chunkX,
                            chunkZ,
                            worldId,
                            chunkKey,
                            null));
                    } catch (Throwable throwable) {
                        ByteBuf fallback = this.serializeLevelChunkWithLight(
                            level,
                            resolvedChunk,
                            chunkX,
                            chunkZ,
                            worldId,
                            chunkKey,
                            null);
                        future.complete(fallback);
                        return;
                    }
                } else {
                    ByteBuf packetData = this.serializeLevelChunkWithLight(
                        level,
                        resolvedChunk,
                        chunkX,
                        chunkZ,
                        worldId,
                        chunkKey,
                        antiXrayProcessor);
                    serializationFuture = CompletableFuture.completedFuture(packetData);
                }
                serializationFuture.whenComplete((packetData, throwable) -> {
                    if (throwable != null) {
                        future.complete(null);
                        return;
                    }
                    future.complete(packetData);
                });
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
                .thenAccept(asyncChunk -> this.runInChunkContext(world, chunkX, chunkZ, scheduler, () -> task.accept(asyncChunk), future))
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
                    this.runInChunkContext(world, chunkX, chunkZ, scheduler, () -> task.accept(chunk), future);
                })
                .exceptionally(throwable -> {
                    future.complete(null);
                    return null;
                });
        }

        return future;
    }

    private LevelChunk resolveLevelChunk(Chunk asyncChunk) {
        if (!(asyncChunk instanceof CraftChunk craftChunk)) {
            return null;
        }
        var access = craftChunk.getHandle(ChunkStatus.FULL);
        if (access instanceof LevelChunk levelChunk) {
            return levelChunk;
        }
        return null;
    }

    private void runInChunkContext(
        World world,
        int chunkX,
        int chunkZ,
        ChunkScheduler scheduler,
        Runnable task,
        CompletableFuture<ByteBuf> future
    ) {
        boolean scheduled = scheduler.runAtChunk(world, chunkX, chunkZ, task);
        if (!scheduled && !future.isDone()) {
            future.complete(null);
        }
    }

    private ByteBuf serializeLevelChunkWithLight(
        ServerLevel level,
        LevelChunk chunk,
        int chunkX,
        int chunkZ,
        UUID worldId,
        long chunkKey,
        AntiXrayProcessor antiXrayProcessor
    ) {
        EhConfig.SerializerMode serializerMode = this.configContainer.get().serializerMode();
        boolean preferFast = serializerMode == EhConfig.SerializerMode.FAST;
        boolean canUseFastChunkData = antiXrayProcessor != null || FastChunkDataWriter.canUseFastPath(chunk);
        boolean useFast = preferFast && canUseFastChunkData;

        int initialCapacity = this.estimatePacketSize(chunk, antiXrayProcessor, useFast);
        ByteBuf raw = PooledByteBufAllocator.DEFAULT.buffer(initialCapacity, Integer.MAX_VALUE);
        FriendlyByteBuf buf = new FriendlyByteBuf(raw);
        try {
            VarInt.write(buf, LEVEL_CHUNK_WITH_LIGHT_PACKET_ID);
            buf.writeInt(chunkX);
            buf.writeInt(chunkZ);

            if (useFast) {
                int payloadStart = buf.writerIndex();
                try {
                    if (antiXrayProcessor != null) {
                        this.writeChunkDataWithAntiXray(buf, chunk, antiXrayProcessor);
                    } else {
                        FastChunkDataWriter.writeChunkData(buf, chunk);
                    }
                    this.writeFastLightWithCache(buf, chunk, worldId, chunkKey);
                    return raw;
                } catch (Throwable throwable) {
                    buf.writerIndex(payloadStart);
                }
            }

            this.writeVanillaChunkAndLight(raw, buf, level, chunk, chunkX, chunkZ);
            return raw;
        } catch (Throwable throwable) {
            raw.release();
            return null;
        }
    }


    private void writeChunkDataWithAntiXray(FriendlyByteBuf out, LevelChunk chunk, AntiXrayProcessor antiXrayProcessor) {
        writeHeightmaps(out, chunk);

        ByteBuf sectionBuffer = PooledByteBufAllocator.DEFAULT.buffer(this.estimateSectionBufferSize(chunk), Integer.MAX_VALUE);
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

    private void writeVanillaChunkAndLight(
        ByteBuf raw,
        FriendlyByteBuf buf,
        ServerLevel level,
        LevelChunk chunk,
        int chunkX,
        int chunkZ
    ) {
        @SuppressWarnings("deprecation") // there isn't a not deprecated way to do this, lol
        ClientboundLevelChunkPacketData chunkData = new ClientboundLevelChunkPacketData(chunk);
        RegistryFriendlyByteBuf registryBuf = new RegistryFriendlyByteBuf(raw, level.registryAccess());
        chunkData.write(registryBuf);
        this.writeVanillaLight(buf, level, chunkX, chunkZ);
    }

    private void writeVanillaLight(FriendlyByteBuf buf, ServerLevel level, int chunkX, int chunkZ) {
        ClientboundLightUpdatePacketData lightData = new ClientboundLightUpdatePacketData(
            new ChunkPos(chunkX, chunkZ),
            level.getLightEngine(),
            null,
            null);
        lightData.write(buf);
    }

    private int estimatePacketSize(LevelChunk chunk, AntiXrayProcessor antiXrayProcessor, boolean useFast) {
        int headerEstimate = 12;
        int chunkEstimate;
        if (useFast && antiXrayProcessor != null) {
            chunkEstimate = this.estimateSectionBufferSize(chunk) + 1024;
        } else if (useFast) {
            chunkEstimate = FastChunkDataWriter.estimateChunkDataSize(chunk);
        } else {
            chunkEstimate = this.estimateSectionBufferSize(chunk) + 2048;
        }

        int lightEstimate = useFast ? FastLightDataWriter.estimateLightDataSize(chunk) : 2048;
        return Math.max(2048, headerEstimate + chunkEstimate + lightEstimate);
    }

    private int estimateSectionBufferSize(LevelChunk chunk) {
        int size = 0;
        for (LevelChunkSection section : chunk.getSections()) {
            size += Math.max(0, section.getSerializedSize());
        }
        return size + 512;
    }

    private void writeFastLightWithCache(FriendlyByteBuf out, LevelChunk chunk, UUID worldId, long chunkKey) {
        ByteBuf cached = this.lightPayloadCacheService.get(worldId, chunkKey);
        if (cached != null) {
            try {
                out.writeBytes(cached, cached.readerIndex(), cached.readableBytes());
                return;
            } finally {
                cached.release();
            }
        }

        int start = out.writerIndex();
        FastLightDataWriter.writeLightData(out, chunk);
        int length = out.writerIndex() - start;
        if (length <= 0) {
            return;
        }

        ByteBuf slice = out.retainedSlice(start, length);
        try {
            this.lightPayloadCacheService.put(worldId, chunkKey, slice);
        } finally {
            slice.release();
        }
    }

    private AntiXrayChunkSnapshot captureAntiXraySnapshot(
        LevelChunk chunk,
        AntiXrayProcessor antiXrayProcessor,
        UUID worldId,
        long chunkKey
    ) {
        ByteBuf heightmaps = PooledByteBufAllocator.DEFAULT.buffer(256, Integer.MAX_VALUE);
        ByteBuf light = this.lightPayloadCacheService.get(worldId, chunkKey);
        AntiXraySectionSnapshot[] sectionSnapshots = null;
        try {
            FriendlyByteBuf heightmapsOut = new FriendlyByteBuf(heightmaps);
            writeHeightmaps(heightmapsOut, chunk);

            int minSectionY = chunk.getMinSectionY();
            LevelChunkSection[] chunkSections = chunk.getSections();
            sectionSnapshots = new AntiXraySectionSnapshot[chunkSections.length];
            for (int i = 0; i < chunkSections.length; i++) {
                LevelChunkSection section = chunkSections[i];
                short nonEmptyBlockCount = getNonEmptyBlockCount(section);
                ByteBuf states = PooledByteBufAllocator.DEFAULT.buffer(Math.max(64, section.getSerializedSize()), Integer.MAX_VALUE);
                ByteBuf biomes = PooledByteBufAllocator.DEFAULT.buffer(64, Integer.MAX_VALUE);
                FriendlyByteBuf statesOut = new FriendlyByteBuf(states);
                FriendlyByteBuf biomesOut = new FriendlyByteBuf(biomes);

                section.getStates().write(statesOut, null, 0);
                section.getBiomes().write(biomesOut, null, 0);

                sectionSnapshots[i] = new AntiXraySectionSnapshot(
                    i + minSectionY,
                    nonEmptyBlockCount,
                    states,
                    biomes
                );
            }

            if (light == null) {
                light = PooledByteBufAllocator.DEFAULT.buffer(FastLightDataWriter.estimateLightDataSize(chunk), Integer.MAX_VALUE);
                FriendlyByteBuf lightOut = new FriendlyByteBuf(light);
                FastLightDataWriter.writeLightData(lightOut, chunk);
                this.lightPayloadCacheService.put(worldId, chunkKey, light);
            }

            return new AntiXrayChunkSnapshot(
                antiXrayProcessor,
                heightmaps,
                light,
                sectionSnapshots
            );
        } catch (Throwable throwable) {
            if (sectionSnapshots != null) {
                for (AntiXraySectionSnapshot sectionSnapshot : sectionSnapshots) {
                    if (sectionSnapshot != null) {
                        sectionSnapshot.release();
                    }
                }
            }
            heightmaps.release();
            if (light != null) {
                light.release();
            }
            return null;
        }
    }

    private ByteBuf serializeAntiXraySnapshot(int chunkX, int chunkZ, AntiXrayChunkSnapshot snapshot) {
        AntiXraySectionSnapshot[] sections = snapshot.sections();
        int sectionCapacity = Math.max(512, this.estimateSectionSnapshotSize(sections));
        ByteBuf sectionBuffer = PooledByteBufAllocator.DEFAULT.buffer(sectionCapacity, Integer.MAX_VALUE);
        try {
            FriendlyByteBuf sectionOut = new FriendlyByteBuf(sectionBuffer);
            for (AntiXraySectionSnapshot section : sections) {
                sectionOut.writeShort(section.nonEmptyBlockCount());

                ByteBuf states = section.states().retainedDuplicate();
                try {
                    int preReader = states.readerIndex();
                    snapshot.antiXrayProcessor().process(states, section.sectionY(), false);
                    states.readerIndex(preReader);
                    sectionOut.writeBytes(states, states.readerIndex(), states.readableBytes());
                } finally {
                    states.release();
                }

                sectionOut.writeBytes(section.biomes(), section.biomes().readerIndex(), section.biomes().readableBytes());
            }

            int sectionBytes = sectionBuffer.readableBytes();

            int estimated = 12
                + snapshot.heightmaps().readableBytes()
                + VarInt.getByteSize(sectionBytes)
                + sectionBytes
                + 1
                + snapshot.light().readableBytes();
            ByteBuf raw = PooledByteBufAllocator.DEFAULT.buffer(Math.max(2048, estimated), Integer.MAX_VALUE);
            FriendlyByteBuf out = new FriendlyByteBuf(raw);
            try {
                VarInt.write(out, LEVEL_CHUNK_WITH_LIGHT_PACKET_ID);
                out.writeInt(chunkX);
                out.writeInt(chunkZ);

                out.writeBytes(snapshot.heightmaps(), snapshot.heightmaps().readerIndex(), snapshot.heightmaps().readableBytes());
                VarIntUtil.writeVarInt(out, sectionBytes);
                out.writeBytes(sectionBuffer, sectionBuffer.readerIndex(), sectionBytes);

                VarIntUtil.writeVarInt(out, 0);
                out.writeBytes(snapshot.light(), snapshot.light().readerIndex(), snapshot.light().readableBytes());
                return raw;
            } catch (Throwable throwable) {
                raw.release();
                return null;
            }
        } finally {
            sectionBuffer.release();
            snapshot.release();
        }
    }

    private int estimateSectionSnapshotSize(AntiXraySectionSnapshot[] sections) {
        int total = 0;
        for (AntiXraySectionSnapshot section : sections) {
            if (section == null) {
                continue;
            }
            total += 2 + section.states().readableBytes() + section.biomes().readableBytes();
        }
        return total;
    }
}
