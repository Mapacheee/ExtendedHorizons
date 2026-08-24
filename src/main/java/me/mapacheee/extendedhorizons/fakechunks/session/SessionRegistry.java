package me.mapacheee.extendedhorizons.fakechunks.session;

import com.thewinterframework.service.annotation.Service;
import com.thewinterframework.service.annotation.lifecycle.OnDisable;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Service
public final class SessionRegistry {

    private final Map<UUID, PlayerSession> sessions = new ConcurrentHashMap<>();
    private volatile boolean stopping;

    public PlayerSession ensureFor(Player player, boolean bumpEpoch) {
        UUID playerId = player.getUniqueId();
        UUID worldId = player.getWorld().getUID();
        if (this.stopping) {
            return closedSession(playerId, worldId);
        }
        PlayerSession result = this.sessions.compute(playerId, (id, current) -> {
            if (this.stopping) {
                if (current != null) {
                    current.close();
                }
                return null;
            }
            if (current == null || current.closed()) {
                current = new PlayerSession(id, worldId);
                current.bumpEpoch();
                return current;
            }
            synchronized (current) {
                boolean worldChanged = !worldId.equals(current.worldId());
                current.setWorld(worldId);
                if (worldChanged || bumpEpoch) {
                    current.handleDimensionReset();
                }
            }
            return current;
        });
        return result == null ? closedSession(playerId, worldId) : result;
    }

    public void forEachSession(Consumer<PlayerSession> action) {
        this.sessions.values().forEach(action);
    }

    public PlayerSession get(UUID playerId) {
        return this.sessions.get(playerId);
    }

    public void remove(UUID playerId) {
        PlayerSession removed = this.sessions.remove(playerId);
        if (removed != null) {
            removed.close();
        }
    }

    @OnDisable
    public void onDisable() {
        this.stopping = true;
        this.sessions.values().forEach(PlayerSession::close);
        this.sessions.clear();
    }

    private static PlayerSession closedSession(UUID playerId, UUID worldId) {
        PlayerSession session = new PlayerSession(playerId, worldId);
        session.close();
        return session;
    }
}

