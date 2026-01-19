package me.mapacheee.extendedhorizons.integration.luckperms;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import com.thewinterframework.service.annotation.lifecycle.OnEnable;
import me.mapacheee.extendedhorizons.shared.service.ConfigService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/*
 * LuckPerms integration service.
 * Resolves per-player maximum view distance using meta or permission nodes.
 * Honors configuration toggles and caches results for a configurable interval.
*/
@Service
public class LuckPermsService {

    private final ConfigService configService;

    private LuckPermsHandler handler;
    private boolean enabled;
    private boolean useGroupPermissions;
    private int cacheTtlSeconds;

    private static class CacheEntry {
        int value;
        long expiresAt;
    }

    private final Map<UUID, CacheEntry> cache = new ConcurrentHashMap<>();

    @Inject
    public LuckPermsService(ConfigService configService) {
        this.configService = configService;
    }

    @OnEnable
    public void init() {
        var cfg = configService.get().integrations().luckperms();
        boolean toggle = cfg != null && cfg.enabled();
        this.enabled = toggle && Bukkit.getPluginManager().isPluginEnabled("LuckPerms");

        if (this.enabled) {
            try {
                this.handler = new LuckPermsHandlerImpl();
            } catch (Throwable t) {
                this.enabled = false;
                this.handler = null;
                Bukkit.getLogger()
                        .warning("[ExtendedHorizons] Failed to initialize LuckPerms integration: " + t.getMessage());
            }
        }

        this.useGroupPermissions = cfg != null && cfg.useGroupPermissions();
        this.cacheTtlSeconds = Math.max(5, cfg != null ? cfg.checkInterval() : 60);
        cache.clear();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Returns the maximum view distance allowed for the player according to
     * LuckPerms.
     * Priority:
     * 1) Meta key: extendedhorizons.max-distance
     * 2) Highest permission matching: extendedhorizons.max.<number> (only if
     * useGroupPermissions=true)
     * 3) Provided fallback value
     */
    public int resolveMaxDistance(Player player, int fallback) {
        if (!enabled || handler == null)
            return -1;

        UUID id = player.getUniqueId();
        long now = Instant.now().getEpochSecond();
        CacheEntry ce = cache.get(id);
        if (ce != null && ce.expiresAt > now) {
            return ce.value;
        }

        int resolved = handler.compute(player, -1, useGroupPermissions);

        CacheEntry fresh = new CacheEntry();
        fresh.value = resolved;
        fresh.expiresAt = now + cacheTtlSeconds;
        cache.put(id, fresh);
        return resolved;
    }

    /**
     * Cleans up cache entry for a player who quit.
     * Prevents cache from growing unbounded over server lifetime.
     * 
     * @param playerId The player UUID to remove from cache
     */
    public void cleanupPlayer(UUID playerId) {
        if (enabled) {
            cache.remove(playerId);
        }
    }

    private interface LuckPermsHandler {
        int compute(Player player, int fallback, boolean useGroupPermissions);
    }

    private static class LuckPermsHandlerImpl implements LuckPermsHandler {
        private final net.luckperms.api.LuckPerms api;

        LuckPermsHandlerImpl() {
            this.api = net.luckperms.api.LuckPermsProvider.get();
        }

        @Override
        public int compute(Player player, int fallback, boolean useGroupPermissions) {
            try {
                net.luckperms.api.model.user.User user = api.getUserManager().getUser(player.getUniqueId());
                if (user == null)
                    return fallback;

                int best = -1;

                for (var node : user.getNodes()) {
                    if (node instanceof net.luckperms.api.node.types.PermissionNode p && p.getValue()) {
                        String perm = p.getPermission();
                        if (perm.startsWith("extendedhorizons.view.")) {
                            Optional<Integer> n = extractTrailingInt(perm);
                            if (n.isPresent() && n.get() > best)
                                best = n.get();
                        }
                    }
                }

                return best > 0 ? best : fallback;

            } catch (Exception e) {
                return fallback;
            }
        }

        private Optional<Integer> extractTrailingInt(String key) {
            int idx = key.lastIndexOf('.') + 1;
            if (idx <= 0 || idx >= key.length())
                return Optional.empty();
            try {
                int val = Integer.parseInt(key.substring(idx));
                return val > 0 ? Optional.of(val) : Optional.empty();
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
    }
}
