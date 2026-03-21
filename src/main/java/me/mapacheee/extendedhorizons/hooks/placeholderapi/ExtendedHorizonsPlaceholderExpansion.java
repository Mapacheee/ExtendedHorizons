package me.mapacheee.extendedhorizons.hooks.placeholderapi;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import com.thewinterframework.service.annotation.lifecycle.OnEnable;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import me.mapacheee.extendedhorizons.chunk.FakeChunkService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@Service
public class ExtendedHorizonsPlaceholderExpansion extends PlaceholderExpansion {

    private final FakeChunkService fakeChunkService;

    @Inject
    public ExtendedHorizonsPlaceholderExpansion(FakeChunkService fakeChunkService) {
        this.fakeChunkService = fakeChunkService;
    }

    @OnEnable
    public void registerExpansion() {
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) return;
        this.register();
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
        ExtendedHorizonsPlugin plugin = ExtendedHorizonsPlugin.getInstance();
        if (plugin == null) return "unknown";
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) return null;
        if ("view_distance".equalsIgnoreCase(params)) {
            return String.valueOf(fakeChunkService.getAdvertisedDistance(player.getUniqueId()));
        }
        return null;
    }
}
