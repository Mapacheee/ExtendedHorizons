package me.mapacheee.extendedhorizons.integration.service;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v1_16.Chunk_v1_9;
import com.github.retrooper.packetevents.protocol.world.chunk.storage.LegacyFlexibleStorage;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUnloadChunk;
import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import me.mapacheee.extendedhorizons.viewdistance.service.ViewDistanceService;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.block.Biome;
import org.bukkit.entity.Player;
import org.slf4j.Logger;

/* PacketEvents Service - Handles all PacketEvents integration for chunk management
 * Manages packet listeners and optimized chunk sending using PacketEvents
 */

@Service
public class PacketEventsService extends PacketListenerAbstract {

    private final Logger logger;
    private final ViewDistanceService viewDistanceService;

    @Inject
    public PacketEventsService(Logger logger, ViewDistanceService viewDistanceService) {
        super(PacketListenerPriority.HIGH);
        this.logger = logger;
        this.viewDistanceService = viewDistanceService;

        registerListener();
    }

    private void registerListener() {
        PacketEvents.getAPI().getEventManager().registerListener(this);
        logger.info("PacketEvents listener registered successfully");
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION ||
            event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {

            handlePlayerMovement(event);
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() == PacketType.Play.Server.CHUNK_DATA) {
            handleChunkDataPacket(event);
        }
    }

    private void handlePlayerMovement(PacketReceiveEvent event) {
        Player player = (Player) event.getPlayer();
        if (player == null) return;

        // Update player movement in view distance service
        Bukkit.getScheduler().runTask(
            Bukkit.getPluginManager().getPlugin("ExtendedHorizons"),
            () -> viewDistanceService.handlePlayerMovement(player)
        );
    }

    private void handleChunkDataPacket(PacketSendEvent event) {
        Player player = (Player) event.getPlayer();
        if (player == null) return;

        // Monitor chunk data being sent for statistics
        WrapperPlayServerChunkData chunkData = new WrapperPlayServerChunkData(event);

        Bukkit.getScheduler().runTask(
            Bukkit.getPluginManager().getPlugin("ExtendedHorizons"),
            () -> {
                viewDistanceService.incrementChunksSent();

                var playerView = viewDistanceService.getPlayerView(player);
                if (playerView != null) {
                    playerView.incrementChunksSent();
                    // Estimate packet size for network monitoring
                    playerView.addNetworkBytesUsed(estimatePacketSize(chunkData));
                }
            }
        );
    }

    public void sendRealChunk(Player player, Chunk chunk) {
        try {
            // Create chunk data packet using PacketEvents
            WrapperPlayServerChunkData chunkPacket = createChunkDataPacket(chunk);
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, chunkPacket);

        } catch (Exception e) {
            logger.error("Failed to send real chunk to player {}", player.getName(), e);
        }
    }

    public void sendFakeChunk(Player player, int chunkX, int chunkZ, byte[] chunkData) {
        try {
            // Create fake chunk data packet
            WrapperPlayServerChunkData fakeChunkPacket = createFakeChunkDataPacket(chunkX, chunkZ, chunkData);
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, fakeChunkPacket);

            viewDistanceService.incrementFakeChunksSent();

        } catch (Exception e) {
            logger.error("Failed to send fake chunk to player {}", player.getName(), e);
        }
    }

    public void sendUnloadChunk(Player player, int chunkX, int chunkZ) {
        try {
            WrapperPlayServerUnloadChunk unloadPacket = new WrapperPlayServerUnloadChunk(chunkX, chunkZ);
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, unloadPacket);

        } catch (Exception e) {
            logger.error("Failed to send unload chunk to player {}", player.getName(), e);
        }
    }

    private WrapperPlayServerChunkData createChunkDataPacket(Chunk chunk) {
        // Create a Column object from the Bukkit chunk for PacketEvents 2.9.5
        try {
            // Get the world and create column
            int chunkX = chunk.getX();
            int chunkZ = chunk.getZ();

            // For PacketEvents 2.9.5, we need to create a Column object
            // This is a simplified approach - in production you'd need proper chunk serialization
            Column column = createColumnFromChunk(chunk);

            return new WrapperPlayServerChunkData(column);
        } catch (Exception e) {
            logger.error("Error creating chunk data packet for chunk {},{}", chunk.getX(), chunk.getZ(), e);
            throw new RuntimeException("Failed to create chunk packet", e);
        }
    }

    private WrapperPlayServerChunkData createFakeChunkDataPacket(int chunkX, int chunkZ, byte[] chunkData) {
        try {
            // Create a fake Column object for the fake chunk
            Column fakeColumn = createFakeColumn(chunkX, chunkZ, chunkData);

            return new WrapperPlayServerChunkData(fakeColumn);
        } catch (Exception e) {
            logger.error("Error creating fake chunk data packet for chunk {},{}", chunkX, chunkZ, e);
            throw new RuntimeException("Failed to create fake chunk packet", e);
        }
    }

    private Column createColumnFromChunk(Chunk chunk) {
        try {
            int chunkX = chunk.getX();
            int chunkZ = chunk.getZ();

            // Create chunk sections (24 sections for Y range -64 to 320)
            BaseChunk[] chunks = new BaseChunk[24];

            // Initialize each section
            for (int sectionY = 0; sectionY < 24; sectionY++) {
                chunks[sectionY] = createChunkSection(chunk, sectionY - 4); // -4 porque empieza en Y=-64
            }

            // Create heightmaps
            int[] heightmap = generateHeightmapFromChunk(chunk);

            // Create biome data (4x4x24 = 384 values)
            int[] biomes = generateBiomeData(chunk);

            return new Column(chunkX, chunkZ, chunks, heightmap, biomes);

        } catch (Exception e) {
            logger.error("Error converting Bukkit chunk to PacketEvents Column", e);
            throw new RuntimeException("Failed to convert chunk", e);
        }
    }

    private Column createFakeColumn(int chunkX, int chunkZ, byte[] chunkData) {
        try {
            // Create simplified fake chunk sections
            BaseChunk[] chunks = new BaseChunk[24];

            // Only create sections that have blocks (surface level)
            for (int sectionY = 0; sectionY < 24; sectionY++) {
                chunks[sectionY] = createFakeChunkSection(chunkX, chunkZ, sectionY - 4, chunkData);
            }

            // Generate simple heightmaps for fake chunks
            int[] heightmap = generateFakeHeightmap(chunkX, chunkZ);

            // Generate fake biome data
            int[] biomes = generateFakeBiomeData(chunkX, chunkZ);

            return new Column(chunkX, chunkZ, chunks, heightmap, biomes);

        } catch (Exception e) {
            logger.error("Error creating fake PacketEvents Column", e);
            throw new RuntimeException("Failed to create fake column", e);
        }
    }

    private BaseChunk createChunkSection(Chunk bukkitChunk, int sectionY) {
        // Calculate actual Y coordinates for this section
        int minY = sectionY * 16;
        int maxY = minY + 15;

        // If this section is outside the world bounds, return air section
        if (maxY < bukkitChunk.getWorld().getMinHeight() || minY > bukkitChunk.getWorld().getMaxHeight()) {
            return createAirSection();
        }

        // Create storage for blocks in this section
        LegacyFlexibleStorage blockStorage = new LegacyFlexibleStorage(4, 4096); // 16x16x16 = 4096 blocks

        // Fill the section with blocks from the Bukkit chunk
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 0; y < 16; y++) {
                    int worldY = minY + y;

                    // Check if this Y coordinate is within world bounds
                    if (worldY >= bukkitChunk.getWorld().getMinHeight() && worldY <= bukkitChunk.getWorld().getMaxHeight()) {
                        try {
                            org.bukkit.Material material = bukkitChunk.getBlock(x, worldY, z).getType();
                            WrappedBlockState blockState = convertMaterialToBlockState(material);

                            int blockIndex = (y * 16 + z) * 16 + x;
                            blockStorage.set(blockIndex, blockState.getGlobalId());
                        } catch (Exception e) {
                            // If there's an error, default to air
                            blockStorage.set((y * 16 + z) * 16 + x, 0); // Air
                        }
                    } else {
                        // Outside world bounds = air
                        blockStorage.set((y * 16 + z) * 16 + x, 0);
                    }
                }
            }
        }

        return new Chunk_v1_9(blockStorage);
    }

    private BaseChunk createFakeChunkSection(int chunkX, int chunkZ, int sectionY, byte[] chunkData) {
        // Create simplified fake section based on our generated terrain
        LegacyFlexibleStorage blockStorage = new LegacyFlexibleStorage(4, 4096);

        // Generate fake terrain for this section
        int minY = sectionY * 16;
        int maxY = minY + 15;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                // Generate height for this position
                int surfaceHeight = generateFakeTerrainHeight(chunkX * 16 + x, chunkZ * 16 + z);

                for (int y = 0; y < 16; y++) {
                    int worldY = minY + y;
                    int blockIndex = (y * 16 + z) * 16 + x;

                    WrappedBlockState blockState;
                    if (worldY <= surfaceHeight) {
                        if (worldY == surfaceHeight) {
                            blockState = StateTypes.GRASS_BLOCK.createBlockState(); // Surface
                        } else if (worldY > surfaceHeight - 3) {
                            blockState = StateTypes.DIRT.createBlockState(); // Sub-surface
                        } else {
                            blockState = StateTypes.STONE.createBlockState(); // Deep
                        }
                    } else {
                        blockState = StateTypes.AIR.createBlockState(); // Air above surface
                    }

                    blockStorage.set(blockIndex, blockState.getGlobalId());
                }
            }
        }

        return new Chunk_v1_9(blockStorage);
    }

    private BaseChunk createAirSection() {
        LegacyFlexibleStorage airStorage = new LegacyFlexibleStorage(4, 4096);
        WrappedBlockState air = StateTypes.AIR.createBlockState();

        // Fill entire section with air
        for (int i = 0; i < 4096; i++) {
            airStorage.set(i, air.getGlobalId());
        }

        return new Chunk_v1_9(airStorage);
    }

    private WrappedBlockState convertMaterialToBlockState(org.bukkit.Material material) {
        // Convert Bukkit Material to PacketEvents BlockState
        return switch (material) {
            case STONE -> StateTypes.STONE.createBlockState();
            case GRASS_BLOCK -> StateTypes.GRASS_BLOCK.createBlockState();
            case DIRT -> StateTypes.DIRT.createBlockState();
            case SAND -> StateTypes.SAND.createBlockState();
            case WATER -> StateTypes.WATER.createBlockState();
            case LAVA -> StateTypes.LAVA.createBlockState();
            case BEDROCK -> StateTypes.BEDROCK.createBlockState();
            case OAK_LOG -> StateTypes.OAK_LOG.createBlockState();
            case OAK_LEAVES -> StateTypes.OAK_LEAVES.createBlockState();
            case COBBLESTONE -> StateTypes.COBBLESTONE.createBlockState();
            default -> StateTypes.AIR.createBlockState(); // Fallback to air for unknown materials
        };
    }

    private int[] generateHeightmapFromChunk(Chunk chunk) {
        int[] heightmap = new int[256]; // 16x16 heightmap

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int height = chunk.getWorld().getHighestBlockYAt(
                    chunk.getX() * 16 + x,
                    chunk.getZ() * 16 + z
                );
                heightmap[z * 16 + x] = height;
            }
        }

        return heightmap;
    }

    private int[] generateFakeHeightmap(int chunkX, int chunkZ) {
        int[] heightmap = new int[256];

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int worldX = chunkX * 16 + x;
                int worldZ = chunkZ * 16 + z;
                int height = generateFakeTerrainHeight(worldX, worldZ);
                heightmap[z * 16 + x] = height;
            }
        }

        return heightmap;
    }

    private int generateFakeTerrainHeight(int worldX, int worldZ) {
        // Simple noise-based terrain generation
        double noise = Math.sin(worldX * 0.01) * Math.cos(worldZ * 0.013);
        noise += Math.sin(worldX * 0.007) * Math.cos(worldZ * 0.011) * 0.5;
        noise += Math.sin(worldX * 0.003) * Math.cos(worldZ * 0.009) * 0.25;

        return (int) (64 + noise * 16); // Base height 64 with variation
    }

    private int[] generateBiomeData(Chunk chunk) {
        // Create biome array for the chunk (4x4x24 sections = 384 values)
        int[] biomes = new int[384];

        // Sample biomes from the chunk
        for (int sectionY = 0; sectionY < 24; sectionY++) {
            for (int x = 0; x < 4; x++) {
                for (int z = 0; z < 4; z++) {
                    int worldX = chunk.getX() * 16 + x * 4;
                    int worldZ = chunk.getZ() * 16 + z * 4;
                    int worldY = (sectionY - 4) * 16; // -4 porque empieza en Y=-64

                    try {
                        org.bukkit.block.Biome biome = chunk.getWorld().getBiome(worldX, worldY, worldZ);
                        int biomeId = convertBiomeToBiomeId(biome);
                        biomes[(sectionY * 4 + z) * 4 + x] = biomeId;
                    } catch (Exception e) {
                        biomes[(sectionY * 4 + z) * 4 + x] = 1; // Plains fallback
                    }
                }
            }
        }

        return biomes;
    }

    private int[] generateFakeBiomeData(int chunkX, int chunkZ) {
        int[] biomes = new int[384];

        // Generate simple fake biome data
        for (int sectionY = 0; sectionY < 24; sectionY++) {
            for (int x = 0; x < 4; x++) {
                for (int z = 0; z < 4; z++) {
                    int worldX = chunkX * 16 + x * 4;
                    int worldZ = chunkZ * 16 + z * 4;

                    // Simple biome generation based on coordinates
                    int biomeId = generateFakeBiome(worldX, worldZ);
                    biomes[(sectionY * 4 + z) * 4 + x] = biomeId;
                }
            }
        }

        return biomes;
    }

    private int generateFakeBiome(int worldX, int worldZ) {
        double temperature = Math.sin(worldX * 0.005) * 0.5 + 0.5;
        double humidity = Math.cos(worldZ * 0.007) * 0.5 + 0.5;

        if (temperature > 0.8) {
            return humidity > 0.5 ? 21 : 2;
        } else if (temperature < 0.2) {
            return humidity > 0.5 ? 30 : 12;
        } else {
            return humidity > 0.5 ? 4 : 1;
        }
    }

    private int convertBiomeToBiomeId(Biome biome) {
        String key = biome.key().asString();

        return switch (key) {
            case "minecraft:ocean" -> 0;
            case "minecraft:plains" -> 1;
            case "minecraft:desert" -> 2;
            case "minecraft:mountains" -> 3;
            case "minecraft:forest" -> 4;
            case "minecraft:taiga" -> 5;
            case "minecraft:swamp" -> 6;
            case "minecraft:river" -> 7;
            case "minecraft:nether_wastes" -> 8;
            case "minecraft:the_end" -> 9;
            case "minecraft:frozen_ocean" -> 10;
            case "minecraft:frozen_river" -> 11;
            case "minecraft:snowy_plains" -> 12;
            case "minecraft:mushroom_fields" -> 14;
            case "minecraft:beach" -> 16;
            case "minecraft:jungle" -> 21;
            case "minecraft:snowy_taiga" -> 30;
            default -> 1; // plains fallback
        };
    }


    private long estimatePacketSize(WrapperPlayServerChunkData chunkData) {
        // Estimate packet size based on chunk data
        return 2048L; // Base chunk packet size estimate
    }

    public boolean isPacketEventsReady() {
        return PacketEvents.getAPI().isInitialized();
    }

    public String getPacketEventsVersion() {
        return PacketEvents.getAPI().getVersion();
    }
}
