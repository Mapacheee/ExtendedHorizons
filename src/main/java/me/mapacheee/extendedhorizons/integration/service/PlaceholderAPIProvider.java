package me.mapacheee.extendedhorizons.integration.service;

import com.google.inject.Inject;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import me.mapacheee.extendedhorizons.shared.config.ConfigService;
import me.mapacheee.extendedhorizons.viewdistance.entity.PlayerView;
import me.mapacheee.extendedhorizons.viewdistance.service.ViewDistanceService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/* PlaceholderAPI Provider - Provides placeholders for ExtendedHorizons integration */

public class PlaceholderAPIProvider extends PlaceholderExpansion {

    private final Logger logger;
    private final ConfigService configService;
    private final ViewDistanceService viewDistanceService;

    private volatile boolean registered = false;

    @Inject
    public PlaceholderAPIProvider(
        Logger logger,
        ConfigService configService,
        ViewDistanceService viewDistanceService
    ) {
        this.logger = logger;
        this.configService = configService;
        this.viewDistanceService = viewDistanceService;
    }

    public void tryRegister() {
        if (registered) return;
        if (!configService.isPlaceholderApiEnabled()) return;
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            logger.info("PlaceholderAPI not found. Skipping placeholder hook.");
            return;
        }
        boolean ok = this.register();
        registered = ok;
        if (ok) {
            logger.info("PlaceholderAPI hook registered (identifier: {}).", getIdentifier());
        } else {
            logger.warn("Failed to register PlaceholderAPI hook.");
        }
    }

    @Override
    public @NotNull String getIdentifier() {
        return "extendedhorizons";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Mapacheee";
    }

    @Override
    public @NotNull String getVersion() {
        return ExtendedHorizonsPlugin.getInstance().getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public @Nullable String onPlaceholderRequest(Player player, @NotNull String params) {
        if (player == null) {
            return null;
        }

        PlayerView view = viewDistanceService.getPlayerView(player);
        if (view == null) {
            return "0";
        }

        switch (params.toLowerCase()) {
            case "distance":
                return String.valueOf(view.getCurrentDistance());
            case "max_distance":
                return String.valueOf(view.getMaxAllowedDistance());
            case "target_distance":
                return String.valueOf(view.getTargetDistance());
            default:
                return null;
        }
    }
}
