package me.mapacheee.extendedhorizons;

import com.thewinterframework.paper.PaperWinterPlugin;
import com.thewinterframework.plugin.WinterBootPlugin;
import me.mapacheee.lib.faststats.Metrics;
import me.mapacheee.lib.faststats.bukkit.BukkitContext;

@WinterBootPlugin
public final class ExtendedHorizonsPlugin extends PaperWinterPlugin {

  private static final String METRICS_TOKEN = "a1d882d1ace0dfbd8ccfa1eef51a4b1e";

  private BukkitContext metrics;

  @Override
  public void onPluginEnable() {
    this.metrics = new BukkitContext.Factory(this, METRICS_TOKEN)
      .metrics(Metrics.Factory::create)
      .create();
    this.metrics.ready();
  }

  @Override
  public void onPluginDisable() {
    if (this.metrics != null) {
      this.metrics.shutdown();
      this.metrics = null;
    }
  }
}
