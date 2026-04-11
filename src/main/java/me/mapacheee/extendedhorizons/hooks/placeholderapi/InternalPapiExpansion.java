package me.mapacheee.extendedhorizons.hooks.placeholderapi;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import me.mapacheee.extendedhorizons.fakechunks.session.PlayerSession;
import me.mapacheee.extendedhorizons.fakechunks.session.SessionRegistry;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class InternalPapiExpansion extends PlaceholderExpansion {

    private final SessionRegistry sessionRegistry;

    public InternalPapiExpansion(SessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
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
        return plugin != null ? plugin.getPluginMeta().getVersion() : "1.0.0";
    }

    @Override
    public boolean persist() {
        return true; 
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer offlinePlayer, @NotNull String params) {
        if (offlinePlayer == null || !offlinePlayer.isOnline()) {
            return null;
        }

        Player player = offlinePlayer.getPlayer();
        if (player == null) {
            return null;
        }

        if (params.equalsIgnoreCase("view_distance")) {
            PlayerSession session = this.sessionRegistry.get(player.getUniqueId());
            if (session != null) {
                return String.valueOf(session.distance());
            }
            return "0";
        }

        return null;
    }
}
