package me.mapacheee.extendedhorizons;

import com.google.inject.Binder;
import com.thewinterframework.paper.PaperWinterPlugin;
import com.thewinterframework.plugin.WinterBootPlugin;
import dev.faststats.bukkit.BukkitContext;
import me.mapacheee.extendedhorizons.fakechunks.backend.ChunkBackend;
import me.mapacheee.extendedhorizons.fakechunks.backend.PaperChunkBackend;
import me.mapacheee.extendedhorizons.fakechunks.disk.RegionFileReader;
import me.mapacheee.extendedhorizons.fakechunks.farplayers.backend.FarPlayerBackend;
import me.mapacheee.extendedhorizons.fakechunks.farplayers.backend.PaperFarPlayerBackend;

@WinterBootPlugin
public final class ExtendedHorizonsPlugin extends PaperWinterPlugin {

  private static final String METRICS_TOKEN = "a1d882d1ace0dfbd8ccfa1eef51a4b1e";

  private BukkitContext context;

  private static volatile ExtendedHorizonsPlugin instance;
  private static volatile boolean loading = false;

  public static ExtendedHorizonsPlugin getInstance() {
    return instance;
  }

  public static <T> T getService(Class<T> type) {
    ExtendedHorizonsPlugin current = instance;
    if (current == null || loading) {
      throw new IllegalStateException("ExtendedHorizons plugin is not loaded yet");
    }
    return current.getInjector().getInstance(type);
  }

  @Override
  public void onPluginEnable() {
    this.context = new BukkitContext.Factory(this, METRICS_TOKEN)
      .metrics(factory -> factory.create())
      .create();

    this.context.ready();
  }

  @Override
  public void onPluginLoad() {
    loading = true;
    try {
      super.onPluginLoad();
      instance = this;
    } finally {
      loading = false;
    }
  }

  @Override
  public void onPluginDisable() {
    RegionFileReader.clearCache();
    if (this.context != null) {
      this.context.shutdown();
    }
    instance = null;
    super.onPluginDisable();
  }

  @Override
  public void configure(Binder binder) {
    binder.bind(ChunkBackend.class).to(PaperChunkBackend.class);
    binder.bind(FarPlayerBackend.class).to(PaperFarPlayerBackend.class);
  }
}
