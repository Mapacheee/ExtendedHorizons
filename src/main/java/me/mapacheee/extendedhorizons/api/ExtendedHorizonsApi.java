package me.mapacheee.extendedhorizons.api;

import org.bukkit.plugin.Plugin;

import java.util.Optional;
import java.util.UUID;

/** Bukkit service for integrations with ExtendedHorizons on Paper and Folia. */
public interface ExtendedHorizonsApi {

  /**
   * Returns the most recent distance calculation, or empty until EH has sampled the session.
   * This describes the target radius. Terrain arrives progressively within that radius.
   */
  Optional<ViewDistance> getViewDistance(UUID playerId);

  /**
   * Sets this plugin's distance cap in chunks, from 2 to 128 inclusive. The cap only
   * limits EH's extension. Vanilla terrain, client settings and server policy still apply.
   * If several plugins set a cap, EH applies the lowest one during its next player tick.
   *
   * @return whether the player has an active EH session and the owner is enabled
   * @throws IllegalArgumentException if chunks is outside the supported range
   */
  boolean setViewDistanceLimit(Plugin owner, UUID playerId, int chunks);

  /** Removes this plugin's cap and returns whether it had one for this player. */
  boolean clearViewDistanceLimit(Plugin owner, UUID playerId);

  /** The native and total target radii, in chunks, for the last sampled player state. */
  record ViewDistance(int nativeDistance, int targetDistance) {
    public boolean extended() {
      return this.targetDistance > this.nativeDistance;
    }
  }
}
