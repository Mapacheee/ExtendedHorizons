package me.mapacheee.extendedhorizons.hooks;

import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.biome.BiomeType;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.mapacheee.extendedhorizons.view.ViewService;
import net.minecraft.world.level.ChunkPos;
import org.bukkit.World;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;

final class ChangedChunkExtent extends AbstractDelegateExtent {

  private final Plugin plugin;
  private final ViewService views;
  private final World world;
  private LongOpenHashSet pending;

  ChangedChunkExtent(Extent delegate, Plugin plugin, ViewService views, World world) {
    super(delegate);
    this.plugin = plugin;
    this.views = views;
    this.world = world;
  }

  @Override
  public <B extends BlockStateHolder<B>> boolean setBlock(BlockVector3 position, B block) throws WorldEditException {
    boolean changed = super.setBlock(position, block);
    if (changed) {
      this.record(position.x(), position.z());
    }
    return changed;
  }

  @Override
  public <B extends BlockStateHolder<B>> boolean setBlock(int x, int y, int z, B block) throws WorldEditException {
    return this.setBlock(BlockVector3.at(x, y, z), block);
  }

  @Override
  public boolean setBiome(BlockVector3 position, BiomeType biome) {
    boolean changed = super.setBiome(position, biome);
    if (changed) {
      this.record(position.x(), position.z());
    }
    return changed;
  }

  @Override
  public boolean setBiome(int x, int y, int z, BiomeType biome) {
    return this.setBiome(BlockVector3.at(x, y, z), biome);
  }

  private synchronized void record(int blockX, int blockZ) {
    if (this.pending == null) {
      if (!this.plugin.isEnabled()) {
        return;
      }
      this.pending = new LongOpenHashSet();
      try {
        this.plugin.getServer().getGlobalRegionScheduler().run(this.plugin, task -> this.clearBatch());
      } catch (IllegalPluginAccessException ignored) {
        this.pending = null;
        return;
      }
    }
    int x = blockX >> 4;
    int z = blockZ >> 4;
    this.mark(x, z);
    int edgeX = (blockX & 15) == 0 ? -1 : (blockX & 15) == 15 ? 1 : 0;
    int edgeZ = (blockZ & 15) == 0 ? -1 : (blockZ & 15) == 15 ? 1 : 0;
    if (edgeX != 0) {
      this.mark(x + edgeX, z);
    }
    if (edgeZ != 0) {
      this.mark(x, z + edgeZ);
    }
  }

  private void mark(int x, int z) {
    long key = ChunkPos.pack(x, z);
    if (this.pending.add(key)) {
      this.views.editedChunk(this.world, key);
    }
  }

  private synchronized void clearBatch() {
    this.pending = null;
  }
}
