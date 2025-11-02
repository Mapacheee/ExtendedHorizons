package me.mapacheee.extendedhorizons.integration.placeholderapi;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import com.thewinterframework.service.annotation.lifecycle.OnEnable;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import me.mapacheee.extendedhorizons.shared.service.ConfigService;
import me.mapacheee.extendedhorizons.viewdistance.entity.PlayerView;
import me.mapacheee.extendedhorizons.viewdistance.service.ViewDistanceService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/*
 * Service for PlaceholderAPI integration.
 * Registers the expansion only if enabled in config and PAPI is present.
 */
@Service
public class PlaceholderService extends PlaceholderExpansion {

    private final ViewDistanceService viewDistanceService;
    private final ConfigService configService;

    @Inject
    public PlaceholderService(ViewDistanceService viewDistanceService, ConfigService configService) {
        this.viewDistanceService = viewDistanceService;
        this.configService = configService;
    }

    @OnEnable
    public void registerPAPI() {
        var cfg = configService.get().integrations().placeholderapi();
        boolean enabled = cfg != null && cfg.enabled();
        if (!enabled) return;
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) return;
        this.register();
    }

    @Override
    public @NotNull String getIdentifier() {
        return ExtendedHorizonsPlugin.getPlugin(ExtendedHorizonsPlugin.class).getPluginMeta().getName().toLowerCase();
    }

    @Override
    public @NotNull String getAuthor() {
        return ExtendedHorizonsPlugin.getPlugin(ExtendedHorizonsPlugin.class).getPluginMeta().getAuthors().toString();
    }

    @Override
    public @NotNull String getVersion() {
        return ExtendedHorizonsPlugin.getPlugin(ExtendedHorizonsPlugin.class).getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) {
            return null;
        }

        PlayerView view = viewDistanceService.getPlayerView(player.getUniqueId());
        if (view == null) {
            return "N/A";
        }

        if ("view_distance".equalsIgnoreCase(params)) {
            return String.valueOf(view.getTargetDistance());
        }

        return null;
    }
}
