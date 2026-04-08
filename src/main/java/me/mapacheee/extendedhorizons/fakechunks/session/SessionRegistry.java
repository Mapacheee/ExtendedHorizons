package me.mapacheee.extendedhorizons.fakechunks.session;

import com.thewinterframework.service.annotation.Service;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public final class SessionRegistry {

    private final Map<UUID, PlayerSession> sessions = new ConcurrentHashMap<>();

    public PlayerSession ensureFor(Player player, boolean bumpEpoch) {
        UUID playerId = player.getUniqueId();
        UUID worldId = player.getWorld().getUID();
        return this.sessions.compute(playerId, (id, current) -> {
            if (current == null) {
                current = new PlayerSession(id, worldId);
                current.bumpEpoch();
                return current;
            }
            boolean worldChanged = !worldId.equals(current.worldId());
            current.setWorld(worldId);
            if (worldChanged || bumpEpoch) {
                current.bumpEpoch();
                current.clearDispatchState();
                current.handleDimensionReset();
            }
            return current;
        });
    }


    public PlayerSession get(UUID playerId) {
        return this.sessions.get(playerId);
    }

    public void remove(UUID playerId) {
        PlayerSession removed = this.sessions.remove(playerId);
        if (removed != null) {
            removed.clearDispatchState();
        }
    }
}

