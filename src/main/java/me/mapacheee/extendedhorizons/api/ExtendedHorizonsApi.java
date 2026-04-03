package me.mapacheee.extendedhorizons.api;

import org.bukkit.entity.Player;

/**
 * Stable public API for ExtendedHorizons.
 * Registered in Bukkit ServicesManager with ExtendedHorizonsApi as service key.
 */
public interface ExtendedHorizonsApi {

  /**
   * Gets the player's custom preferred view distance in chunks.
   *
   * @param player Player to check.
   * @return Custom distance, or -1 if no custom preference exists.
   */
  int getPlayerViewDistance(Player player);

  /**
   * Sets a custom preferred view distance for a player.
   *
   * @param player Player to update.
   * @param viewDistance New target view distance in chunks.
   */
  void setPlayerViewDistance(Player player, int viewDistance);

  /**
   * Removes custom view distance preference for a player.
   *
   * @param player Player to reset.
   */
  void resetPlayerViewDistance(Player player);

  /**
   * Gets the global max target view distance from current config.
   *
   * @return Global target view distance in chunks.
   */
  int getServerMaxViewDistance();

  /**
   * Checks whether fake chunks are enabled for a world.
   *
   * @param worldName World name.
   * @return True when enabled for that world.
   */
  boolean isFakeChunksEnabled(String worldName);

  /**
   * Checks whether far-player sync is globally enabled.
   *
   * @return True when far-player sync is enabled.
   */
  boolean isFarPlayersEnabled();
}
