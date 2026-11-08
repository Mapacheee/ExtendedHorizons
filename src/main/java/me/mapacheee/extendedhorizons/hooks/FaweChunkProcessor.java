package me.mapacheee.extendedhorizons.hooks;

import me.mapacheee.extendedhorizons.compat.PacketAccess;
import com.fastasyncworldedit.core.configuration.Settings;
import com.fastasyncworldedit.core.extent.processor.ProcessorScope;
import com.fastasyncworldedit.core.queue.IBatchProcessor;
import com.fastasyncworldedit.core.queue.IChunk;
import com.fastasyncworldedit.core.queue.IChunkGet;
import com.fastasyncworldedit.core.queue.IChunkSet;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extent.Extent;
import me.mapacheee.extendedhorizons.view.ViewService;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

record FaweChunkProcessor(Plugin plugin, ViewService views, World world) implements IBatchProcessor {

  static boolean attach(EditSessionEvent event, Plugin plugin, ViewService views, World world) {
    Extent extent = event.getExtent();
    Extent hooked = extent.addPostProcessor(new FaweChunkProcessor(plugin, views, world));
    if (hooked != extent) {
      String name = hooked.getClass().getName().toLowerCase(Locale.ROOT);
      if (Settings.settings().EXTENT.ALLOWED_PLUGINS.stream().noneMatch(entry -> name.contains(entry.toLowerCase(Locale.ROOT)))) {
        return false;
      }
      event.setExtent(hooked);
    }
    return true;
  }

  @Override
  public IChunkSet processSet(IChunk chunk, IChunkGet get, IChunkSet set) {
    return set;
  }

  @Override
  public Future<?> postProcessSet(IChunk chunk, IChunkGet get, IChunkSet set) {
    int x = chunk.getX();
    int z = chunk.getZ();
    this.views.editedChunk(this.world, PacketAccess.chunkKey(x, z));
    this.views.editedChunk(this.world, PacketAccess.chunkKey(x - 1, z));
    this.views.editedChunk(this.world, PacketAccess.chunkKey(x + 1, z));
    this.views.editedChunk(this.world, PacketAccess.chunkKey(x, z - 1));
    this.views.editedChunk(this.world, PacketAccess.chunkKey(x, z + 1));
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public Extent construct(Extent child) {
    return new ChangedChunkExtent(child, this.plugin, this.views, this.world);
  }

  @Override
  public ProcessorScope getScope() {
    return ProcessorScope.READING_BLOCKS;
  }
}
