package me.mapacheee.extendedhorizons.fakechunks.listener;

import com.google.inject.Inject;
import com.thewinterframework.paper.listener.ListenerComponent;
import io.netty.channel.Channel;
import me.mapacheee.extendedhorizons.fakechunks.cache.AntiXrayPayloadCacheService;
import me.mapacheee.extendedhorizons.fakechunks.cache.ChunkBuildCacheService;
import me.mapacheee.extendedhorizons.fakechunks.cache.LightPayloadCacheService;
import me.mapacheee.extendedhorizons.fakechunks.netty.ChannelInjectionService;
import me.mapacheee.extendedhorizons.fakechunks.session.SessionRegistry;
import me.mapacheee.extendedhorizons.fakechunks.util.ChunkKeyCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.UUID;

@ListenerComponent
public final class ChunkInvalidationListener implements Listener {

    private static final int CHUNK_SHIFT = 4;

    private final ChunkBuildCacheService chunkBuildCacheService;
    private final AntiXrayPayloadCacheService antiXrayPayloadCacheService;
    private final LightPayloadCacheService lightPayloadCacheService;
    private final SessionRegistry sessionRegistry;
    private final ChannelInjectionService channelInjectionService;

    @Inject
    public ChunkInvalidationListener(
        ChunkBuildCacheService chunkBuildCacheService,
        AntiXrayPayloadCacheService antiXrayPayloadCacheService,
        LightPayloadCacheService lightPayloadCacheService,
        SessionRegistry sessionRegistry,
        ChannelInjectionService channelInjectionService
    ) {
        this.chunkBuildCacheService = chunkBuildCacheService;
        this.antiXrayPayloadCacheService = antiXrayPayloadCacheService;
        this.lightPayloadCacheService = lightPayloadCacheService;
        this.sessionRegistry = sessionRegistry;
        this.channelInjectionService = channelInjectionService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        this.invalidateCaches(block);
        this.broadcastBlockChange(block, Material.AIR.createBlockData());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block placed = event.getBlockPlaced();
        this.invalidateCaches(placed);
        this.broadcastBlockChange(placed, placed.getBlockData());
    }

    private void invalidateCaches(Block block) {
        int chunkX = block.getX() >> CHUNK_SHIFT;
        int chunkZ = block.getZ() >> CHUNK_SHIFT;
        long chunkKey = ChunkKeyCodec.pack(chunkX, chunkZ);
        UUID worldId = block.getWorld().getUID();
        this.chunkBuildCacheService.invalidate(worldId, chunkKey);
        this.antiXrayPayloadCacheService.invalidateChunk(worldId, chunkKey);
        this.lightPayloadCacheService.invalidate(worldId, chunkKey);
        this.sessionRegistry.forEachSession(session -> {
            if (worldId.equals(session.worldId())) {
                session.invalidatePendingChunk(chunkKey);
            }
        });
    }

    private void broadcastBlockChange(Block block, BlockData blockData) {
        UUID worldId = block.getWorld().getUID();
        int chunkX = block.getX() >> CHUNK_SHIFT;
        int chunkZ = block.getZ() >> CHUNK_SHIFT;
        long chunkKey = ChunkKeyCodec.pack(chunkX, chunkZ);

        BlockPos pos = new BlockPos(block.getX(), block.getY(), block.getZ());
        BlockState nmsState = ((CraftBlockData) blockData).getState();
        ClientboundBlockUpdatePacket packet = new ClientboundBlockUpdatePacket(pos, nmsState);

        this.sessionRegistry.forEachSession(session -> {
            if (!worldId.equals(session.worldId())) {
                return;
            }
            if (session.shouldReceiveBlockUpdate(chunkKey)) {
                Player player = Bukkit.getPlayer(session.playerId());
                if (player != null) {
                    Channel channel = this.channelInjectionService.resolveChannel(player);
                    if (channel != null && channel.isActive()) {
                        this.channelInjectionService.writeBypass(channel, packet);
                        this.channelInjectionService.flush(channel);
                    }
                }
            }
        });
    }
}
