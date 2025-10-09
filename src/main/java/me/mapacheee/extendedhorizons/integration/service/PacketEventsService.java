package me.mapacheee.extendedhorizons.integration.service;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.chunk.TileEntity;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v1_16.Chunk_v1_9;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.DataPalette;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.ListPalette;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.PaletteType;
import com.github.retrooper.packetevents.protocol.world.chunk.storage.LegacyFlexibleStorage;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUnloadChunk;
import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import me.mapacheee.extendedhorizons.viewdistance.service.IViewDistanceService;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.block.Biome;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

/* PacketEvents Service - Handles all PacketEvents integration for chunk management
 * Manages packet listeners and optimized chunk sending using PacketEvents
 */

@Service
public class PacketEventsService extends PacketListenerAbstract implements IPacketEventsService {

    private final Logger logger;
    private IViewDistanceService viewDistanceService;
    private final Plugin plugin;

    @Inject
    public PacketEventsService(Logger logger, ExtendedHorizonsPlugin plugin) {
        super(PacketListenerPriority.HIGH);
        this.logger = logger;
        this.viewDistanceService = null;
        this.plugin = plugin;

        registerListener();
    }

    @Inject
    public void setViewDistanceService(IViewDistanceService viewDistanceService) {
        this.viewDistanceService = viewDistanceService;
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
        Player player = event.getPlayer();

        if (viewDistanceService != null) {
            Bukkit.getScheduler().runTask(plugin, () -> viewDistanceService.updatePlayerView(player));
        }
    }

    private void handleChunkDataPacket(PacketSendEvent event) {
        Player player = event.getPlayer();

        // Monitor chunk data being sent for statistics
        if (viewDistanceService != null) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                var playerView = viewDistanceService.getPlayerView(player.getUniqueId());
                if (playerView != null) {
                    playerView.incrementChunksSent();
                    playerView.addNetworkBytesUsed(estimatePacketSize());
                }
            });
        }
    }

    public void sendRealChunk(Player player, Chunk chunk) {
        try {
            WrapperPlayServerChunkData chunkPacket = createChunkDataPacket(chunk);
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, chunkPacket);

        } catch (Exception e) {
            logger.error("Failed to send real chunk to player {}", player.getName(), e);
        }
    }

    public void sendFakeChunk(Player player, int chunkX, int chunkZ) {
        try {
            // Create fake chunk data packet
            WrapperPlayServerChunkData fakeChunkPacket = createFakeChunkDataPacket(chunkX, chunkZ);
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, fakeChunkPacket);

            // Update fake chunk statistics through player view
            if (viewDistanceService != null) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    var playerView = viewDistanceService.getPlayerView(player.getUniqueId());
                    if (playerView != null) {
                        playerView.incrementChunksSent(); // Using available method for fake chunks too
                    }
                });
            }

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

    @Override
    public void sendChunkPacket(Player player, WrapperPlayServerChunkData chunkData) {
        try {
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, chunkData);
        } catch (Exception e) {
            logger.error("Failed to send chunk packet to player {}", player.getName(), e);
        }
    }

    @Override
    public void sendFakeChunkPacket(Player player, int chunkX, int chunkZ) {
        sendFakeChunk(player, chunkX, chunkZ);
    }

    @Override
    public void sendUnloadChunkPacket(Player player, int chunkX, int chunkZ) {
        sendUnloadChunk(player, chunkX, chunkZ);
    }

    @Override
    public WrapperPlayServerChunkData createChunkData(Chunk chunk) {
        return createChunkDataPacket(chunk);
    }

    @Override
    public WrapperPlayServerChunkData createFakeChunkData(int chunkX, int chunkZ) {
        return createFakeChunkDataPacket(chunkX, chunkZ);
    }

    @Override
    public void unregisterListener() {
        PacketEvents.getAPI().getEventManager().unregisterListener(this);
        logger.info("PacketEvents listener unregistered");
    }

    private WrapperPlayServerChunkData createChunkDataPacket(Chunk chunk) {
        try {
            Column column = createColumnFromChunk(chunk);

            return new WrapperPlayServerChunkData(column);
        } catch (Exception e) {
            logger.error("Error creating chunk data packet for chunk {},{}", chunk.getX(), chunk.getZ(), e);
            throw new RuntimeException("Failed to create chunk packet", e);
        }
    }

    private WrapperPlayServerChunkData createFakeChunkDataPacket(int chunkX, int chunkZ) {
        try {
            Column fakeColumn = createFakeColumn(chunkX, chunkZ);

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

            BaseChunk[] chunks = new BaseChunk[24];

            for (int sectionY = 0; sectionY < 24; sectionY++) {
                chunks[sectionY] = createChunkSection(chunk, sectionY - 4);
            }

            return new Column(chunkX, chunkZ, false, chunks, new TileEntity[0]);

        } catch (Exception e) {
            logger.error("Error converting Bukkit chunk to PacketEvents Column", e);
            throw new RuntimeException("Failed to convert chunk", e);
        }
    }

    private Column createFakeColumn(int chunkX, int chunkZ) {
        try {
            // Create simplified fake chunk sections
            BaseChunk[] chunks = new BaseChunk[24];

            // Only create sections that have blocks (surface level)
            for (int sectionY = 0; sectionY < 24; sectionY++) {
                chunks[sectionY] = createFakeChunkSection(chunkX, chunkZ, sectionY - 4);
            }

            return new Column(chunkX, chunkZ, false, chunks, new TileEntity[0]);

        } catch (Exception e) {
            logger.error("Error creating fake PacketEvents Column", e);
            throw new RuntimeException("Failed to create fake column", e);
        }
    }

    private BaseChunk createChunkSection(Chunk bukkitChunk, int sectionY) {
        int minY = sectionY * 16;
        int maxY = minY + 15;

        if (maxY < bukkitChunk.getWorld().getMinHeight() || minY > bukkitChunk.getWorld().getMaxHeight()) {
            return createAirSection();
        }

        LegacyFlexibleStorage blockStorage = new LegacyFlexibleStorage(4, 4096);
        ListPalette blockPalette = new ListPalette(4);

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 0; y < 16; y++) {
                    int worldY = minY + y;
                    int blockIndex = (y * 16 + z) * 16 + x;

                    // Check if this Y coordinate is within world bounds
                    if (worldY >= bukkitChunk.getWorld().getMinHeight() && worldY <= bukkitChunk.getWorld().getMaxHeight()) {
                        try {
                            org.bukkit.Material material = bukkitChunk.getBlock(x, worldY, z).getType();
                            int blockId = convertMaterialToBlockId(material);
                            blockStorage.set(blockIndex, blockId);
                        } catch (Exception e) {
                            // If there's an error, default to air (ID 0)
                            blockStorage.set(blockIndex, 0);
                        }
                    } else {
                        // Outside world bounds = air (ID 0)
                        blockStorage.set(blockIndex, 0);
                    }
                }
            }
        }

        DataPalette dataPalette = new DataPalette(blockPalette, blockStorage, PaletteType.CHUNK);
        return new Chunk_v1_9(0, dataPalette);
    }

    private BaseChunk createFakeChunkSection(int chunkX, int chunkZ, int sectionY) {
        LegacyFlexibleStorage blockStorage = new LegacyFlexibleStorage(4, 4096);
        ListPalette blockPalette = new ListPalette(4);

        // Generate fake terrain for this section
        int minY = sectionY * 16;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                // Generate height for this position
                int surfaceHeight = generateFakeTerrainHeight(chunkX * 16 + x, chunkZ * 16 + z);

                for (int y = 0; y < 16; y++) {
                    int worldY = minY + y;
                    int blockIndex = (y * 16 + z) * 16 + x;

                    int blockId;
                    if (worldY <= surfaceHeight) {
                        if (worldY == surfaceHeight) {
                            blockId = 2; // Grass block
                        } else if (worldY > surfaceHeight - 3) {
                            blockId = 3; // Dirt
                        } else {
                            blockId = 1; // Stone
                        }
                    } else {
                        blockId = 0; // Air
                    }

                    blockStorage.set(blockIndex, blockId);
                }
            }
        }

        DataPalette dataPalette = new DataPalette(blockPalette, blockStorage, PaletteType.CHUNK);
        return new Chunk_v1_9(sectionY, dataPalette); // Section Y and DataPalette
    }

    private BaseChunk createAirSection() {
        LegacyFlexibleStorage airStorage = new LegacyFlexibleStorage(4, 4096);
        ListPalette airPalette = new ListPalette(4);

        for (int i = 0; i < 4096; i++) {
            airStorage.set(i, 0);
        }

        DataPalette dataPalette = new DataPalette(airPalette, airStorage, PaletteType.CHUNK);
        return new Chunk_v1_9(0, dataPalette); // Section Y and DataPalette
    }

    private int convertMaterialToBlockId(org.bukkit.Material material) {
        return switch (material) {
            case STONE -> 1;
            case GRASS_BLOCK -> 2;
            case DIRT -> 3;
            case COBBLESTONE -> 4;
            case OAK_PLANKS -> 5;
            case BEDROCK -> 7;
            case WATER -> 8;
            case LAVA -> 10;
            case SAND -> 12;
            case GRAVEL -> 13;
            case OAK_LOG -> 17;
            case OAK_LEAVES -> 18;
            default -> 0; // Air for unknown materials
        };
    }

    private int generateFakeTerrainHeight(int worldX, int worldZ) {
        // Simple noise-based terrain generation
        double noise = Math.sin(worldX * 0.01) * Math.cos(worldZ * 0.013);
        noise += Math.sin(worldX * 0.007) * Math.cos(worldZ * 0.011) * 0.5;
        noise += Math.sin(worldX * 0.003) * Math.cos(worldZ * 0.009) * 0.25;

        return (int) (64 + noise * 16);
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
            default -> 1;
        };
    }

    private long estimatePacketSize() {
        return 2048L;
    }

    public boolean isPacketEventsReady() {
        return PacketEvents.getAPI().isInitialized();
    }

    public String getPacketEventsVersion() {
        return PacketEvents.getAPI().getVersion().toString();
    }
}
