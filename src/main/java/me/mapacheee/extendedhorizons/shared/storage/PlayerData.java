package me.mapacheee.extendedhorizons.shared.storage;

import java.util.UUID;

/*
 * Represents the persistent data for a player.
 * This object stores the player's unique identifier and their
 * custom view distance setting.
 */
public class PlayerData {

    private final UUID uuid;
    private int viewDistance;

    public PlayerData(UUID uuid, int viewDistance) {
        this.uuid = uuid;
        this.viewDistance = viewDistance;
    }

    public UUID getUuid() {
        return uuid;
    }

    public int getViewDistance() {
        return viewDistance;
    }

    public void setViewDistance(int viewDistance) {
        this.viewDistance = viewDistance;
    }
}

