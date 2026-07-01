package me.mapacheee.extendedhorizons.fakechunks.listener;

import com.google.inject.Inject;
import com.thewinterframework.paper.listener.ListenerComponent;
import me.mapacheee.extendedhorizons.fakechunks.util.ChunkKeyCodec;
import me.mapacheee.extendedhorizons.hooks.worldedit.BulkChunkInvalidationService;
import me.mapacheee.extendedhorizons.fakechunks.session.SessionRegistry;
import me.mapacheee.extendedhorizons.fakechunks.netty.ChannelInjectionService;
import io.netty.channel.Channel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
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

    private final BulkChunkInvalidationService bulkService;
    private final SessionRegistry sessionRegistry;
    private final ChannelInjectionService channelInjectionService;

    @Inject
    public ChunkInvalidationListener(
        BulkChunkInvalidationService bulkService,
        SessionRegistry sessionRegistry,
        ChannelInjectionService channelInjectionService
    ) {
        this.bulkService = bulkService;
        this.sessionRegistry = sessionRegistry;
        this.channelInjectionService = channelInjectionService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        this.invalidate(block);
        this.broadcastBlockChange(block, org.bukkit.Material.AIR.createBlockData());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block placed = event.getBlockPlaced();
        this.invalidate(placed);
        this.broadcastBlockChange(placed, placed.getBlockData());
    }

    private void invalidate(Block block) {
        int chunkX = block.getX() >> CHUNK_SHIFT;
        int chunkZ = block.getZ() >> CHUNK_SHIFT;
        long chunkKey = ChunkKeyCodec.pack(chunkX, chunkZ);
        UUID worldId = block.getWorld().getUID();
        this.bulkService.queueInvalidation(worldId, chunkKey);
    }

    private void broadcastBlockChange(Block block, org.bukkit.block.data.BlockData blockData) {
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
            if (session.isEhLoaded(chunkKey)) {
                Player player = Bukkit.getPlayer(session.playerId());
                if (player != null) {
                    Channel channel = this.channelInjectionService.resolveChannel(player);
                    if (channel != null && channel.isActive()) {
                        this.channelInjectionService.writeBypass(channel, packet);
                    }
                }
            }
        });
    }
}
