package me.mapacheee.extendedhorizons.hooks;

import com.google.inject.Inject;
import com.thewinterframework.component.annotation.Component;
import com.thewinterframework.component.annotation.SyncLoading;
import com.thewinterframework.service.annotation.lifecycle.OnDisable;
import com.thewinterframework.service.annotation.lifecycle.OnEnable;
import me.mapacheee.extendedhorizons.view.ViewService;
import org.bukkit.plugin.Plugin;

@Component
@SyncLoading
public final class EditIntegration {

  private final Plugin plugin;
  private final ViewService views;
  private Runnable unregister;

  @Inject
  public EditIntegration(Plugin plugin, ViewService views) {
    this.plugin = plugin;
    this.views = views;
  }

  @OnEnable
  public void start() {
    var plugins = this.plugin.getServer().getPluginManager();
    boolean fawe = plugins.isPluginEnabled("FastAsyncWorldEdit");
    if (fawe || plugins.isPluginEnabled("WorldEdit")) {
      try {
        this.unregister = WorldEditHook.register(this.plugin, this.views, fawe);
      } catch (RuntimeException | LinkageError error) {
        this.plugin.getSLF4JLogger().warn("Could not register the chunk updates hook for {}.",
          fawe ? "FastAsyncWorldEdit" : "WorldEdit", error);
      }
    }
  }

  @OnDisable
  public void stop() {
    if (this.unregister != null) {
      this.unregister.run();
      this.unregister = null;
    }
  }
}
