package me.mapacheee.extendedhorizons.commands;

import com.google.inject.Inject;
import com.thewinterframework.command.CommandComponent;
import com.thewinterframework.service.ReloadServiceManager;
import me.mapacheee.extendedhorizons.view.ViewService;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.paper.util.sender.Source;

@CommandComponent
public final class Commands {

  private final ViewService views;
  private final ReloadServiceManager reloadManager;

  @Inject
  public Commands(ViewService views, ReloadServiceManager reloadManager) {
    this.views = views;
    this.reloadManager = reloadManager;
  }

  @Command("eh")
  @Permission("extendedhorizons.admin")
  public void root(Source source) {
    this.stats(source);
  }

  @Command("eh stats")
  @Permission("extendedhorizons.admin")
  public void stats(Source source) {
    var sender = source.source();
    sender.sendMessage("ExtendedHorizons: " + this.views.sessionCount() + " sessions");
    sender.sendMessage("Delivery: " + this.views.deliveryStats());
    sender.sendMessage("Chunk source: " + this.views.sourceStats());
  }

  @Command("eh reload")
  @Permission("extendedhorizons.admin")
  public void reload(Source source) {
    var result = this.reloadManager.reload();
    source.source().sendMessage(result.result() ? "Config reloaded!" : "Reload failed. Check the server log.");
  }
}
