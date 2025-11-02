package me.mapacheee.extendedhorizons.integration.luckperms;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import com.thewinterframework.service.annotation.lifecycle.OnEnable;
import me.mapacheee.extendedhorizons.shared.service.ConfigService;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.types.PermissionNode;
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

    private LuckPerms api;
    private boolean enabled;
    private boolean useGroupPermissions;
    private int cacheTtlSeconds;

    private static class CacheEntry { int value; long expiresAt; }
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
        if (enabled) {
            this.api = Bukkit.getServicesManager().load(LuckPerms.class);
            this.enabled = (this.api != null);
        }
        this.useGroupPermissions = cfg != null && cfg.useGroupPermissions();
        this.cacheTtlSeconds = Math.max(5, cfg != null ? cfg.checkInterval() : 60);
        cache.clear();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Returns the maximum view distance allowed for the player according to LuckPerms.
     * Priority:
     * 1) Meta key: extendedhorizons.max-distance
     * 2) Highest permission matching: extendedhorizons.max.<number> (only if useGroupPermissions=true)
     * 3) Provided fallback value
     */
    public int resolveMaxDistance(Player player, int fallback) {
        if (!enabled) return fallback;
        UUID id = player.getUniqueId();
        long now = Instant.now().getEpochSecond();
        CacheEntry ce = cache.get(id);
        if (ce != null && ce.expiresAt > now) {
            return ce.value;
        }
        int resolved = compute(player, fallback);
        CacheEntry fresh = new CacheEntry();
        fresh.value = resolved;
        fresh.expiresAt = now + cacheTtlSeconds;
        cache.put(id, fresh);
        return resolved;
    }

    private int compute(Player player, int fallback) {
        try {
            User user = api.getUserManager().getUser(player.getUniqueId());
            if (user == null) return fallback;

            CachedMetaData meta = user.getCachedData().getMetaData();
            String metaValue = meta.getMetaValue("extendedhorizons.max-distance");
            if (metaValue != null) {
                Integer parsed = parsePositiveInt(metaValue);
                if (parsed != null) return parsed;
            }

            if (useGroupPermissions) {
                int best = -1;
                for (var node : user.getNodes()) {
                    if (node instanceof PermissionNode p && p.getValue()) {
                        String perm = p.getPermission();
                        if (perm.startsWith("extendedhorizons.max.")) {
                            Optional<Integer> n = extractTrailingInt(perm);
                            if (n.isPresent() && n.get() > best) best = n.get();
                        }
                    }
                }
                if (best > 0) return best;
            }
            return fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static Optional<Integer> extractTrailingInt(String key) {
        int idx = key.lastIndexOf('.') + 1;
        if (idx <= 0 || idx >= key.length()) return Optional.empty();
        try {
            int val = Integer.parseInt(key.substring(idx));
            return val > 0 ? Optional.of(val) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Integer parsePositiveInt(String s) {
        try {
            int v = Integer.parseInt(s.trim());
            return v > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
