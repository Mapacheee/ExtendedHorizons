package me.mapacheee.extendedhorizons.hooks.worldedit;

import com.google.inject.Inject;
import com.thewinterframework.configurate.Container;
import com.thewinterframework.paper.listener.ListenerComponent;
import me.mapacheee.extendedhorizons.config.EhConfig;
import me.mapacheee.extendedhorizons.fakechunks.util.ChunkKeyCodec;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.Locale;

@ListenerComponent
public final class CommandInvalidationListener implements Listener {

    private static final Logger LOGGER = LoggerFactory.getLogger(CommandInvalidationListener.class);
    static final long MAX_COMMAND_CHUNKS = 4096L;
    private static final int WORLD_LIMIT = 30_000_000;

    private final BulkChunkInvalidationService bulkChunkInvalidationService;
    private final Container<EhConfig> configContainer;

    @Inject
    public CommandInvalidationListener(
        BulkChunkInvalidationService bulkChunkInvalidationService,
        Container<EhConfig> configContainer
    ) {
        this.bulkChunkInvalidationService = bulkChunkInvalidationService;
        this.configContainer = configContainer;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (!this.configContainer.get().worldEditEnabled()) return;
        this.handleVanillaCommand(event.getPlayer(), event.getMessage());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        if (!this.configContainer.get().worldEditEnabled()) return;
        this.handleVanillaCommand(event.getSender(), "/" + event.getCommand());
    }

    private void handleVanillaCommand(CommandSender sender, String commandLine) {
        String lowerCmd = commandLine.toLowerCase(Locale.ROOT);
        if (!lowerCmd.startsWith("/fill ") && !lowerCmd.startsWith("/clone ")) {
            return;
        }

        boolean clone = lowerCmd.startsWith("/clone ");
        if (!sender.hasPermission(clone ? "minecraft.command.clone" : "minecraft.command.fill")) {
            return;
        }
        String[] args = commandLine.trim().split("\\s+");
        if (args.length < (clone ? 10 : 8)) {
            return;
        }

        Location sourceLoc = null;
        World world = null;

        if (sender instanceof Entity entity) {
            sourceLoc = entity.getLocation();
            world = entity.getWorld();
        } else if (sender instanceof BlockCommandSender blockSender) {
            sourceLoc = blockSender.getBlock().getLocation();
            world = blockSender.getBlock().getWorld();
        }

        if (world == null) {
            return;
        }

        try {
            int x1 = this.parseCoordinate(args[1], sourceLoc.getX());
            int z1 = this.parseCoordinate(args[3], sourceLoc.getZ());

            int x2 = this.parseCoordinate(args[4], sourceLoc.getX());
            int z2 = this.parseCoordinate(args[6], sourceLoc.getZ());

            int minX = Math.min(x1, x2);
            int maxX = Math.max(x1, x2);
            int minZ = Math.min(z1, z2);
            int maxZ = Math.max(z1, z2);

            int minChunkX = minX >> 4;
            int maxChunkX = maxX >> 4;
            int minChunkZ = minZ >> 4;
            int maxChunkZ = maxZ >> 4;

            long sourceCount = chunkCount(minX, maxX, minZ, maxZ);
            int dx = clone ? parseCoordinate(args[7], sourceLoc.getX()) : 0;
            int dz = clone ? parseCoordinate(args[9], sourceLoc.getZ()) : 0;
            long dMaxX = (long) dx + maxX - minX;
            long dMaxZ = (long) dz + maxZ - minZ;
            long destinationCount = clone ? chunkCount(dx, dMaxX, dz, dMaxZ) : 0;
            if (sourceCount < 0 || destinationCount < 0
                || sourceCount + destinationCount > MAX_COMMAND_CHUNKS) {
                return;
            }

            UUID worldId = world.getUID();

            for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                    long chunkKey = ChunkKeyCodec.pack(cx, cz);
                    this.bulkChunkInvalidationService.queueInvalidation(worldId, chunkKey);
                }
            }

            if (clone) {
                int dMinChunkX = dx >> 4;
                int dMaxChunkX = (int) (dMaxX >> 4);
                int dMinChunkZ = dz >> 4;
                int dMaxChunkZ = (int) (dMaxZ >> 4);

                for (int cx = dMinChunkX; cx <= dMaxChunkX; cx++) {
                    for (int cz = dMinChunkZ; cz <= dMaxChunkZ; cz++) {
                        long chunkKey = ChunkKeyCodec.pack(cx, cz);
                        this.bulkChunkInvalidationService.queueInvalidation(worldId, chunkKey);
                    }
                }
            }

        } catch (NumberFormatException error) {
            LOGGER.debug("Could not parse coordinates for command {} (Invalid format).", lowerCmd, error);
        }
    }

    static long chunkCount(long minX, long maxX, long minZ, long maxZ) {
        if (minX < -WORLD_LIMIT || minZ < -WORLD_LIMIT || maxX > WORLD_LIMIT
            || maxZ > WORLD_LIMIT || minX > maxX || minZ > maxZ) return -1;
        return ((maxX >> 4) - (minX >> 4) + 1) * ((maxZ >> 4) - (minZ >> 4) + 1);
    }

    static int parseCoordinate(String arg, double sourceCoord) throws NumberFormatException {
        if (arg.startsWith("^")) throw new NumberFormatException("Local coordinates require command context");
        double value = arg.startsWith("~")
            ? sourceCoord + (arg.length() == 1 ? 0 : Double.parseDouble(arg.substring(1)))
            : Double.parseDouble(arg);
        if (!Double.isFinite(value) || value < -WORLD_LIMIT || value > WORLD_LIMIT) {
            throw new NumberFormatException("Coordinate outside world bounds");
        }
        return (int) Math.floor(value);
    }
}
