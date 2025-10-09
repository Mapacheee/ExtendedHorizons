package me.mapacheee.extendedhorizons.integration.service;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import me.mapacheee.extendedhorizons.shared.config.ConfigService;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.slf4j.Logger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/* LuckPerms Integration Service - Handles permission-based view distance limits
 * Integrates with LuckPerms to provide group and user-specific view distance permissions
 */

@Service
public class LuckPermsIntegrationService {

    private final Logger logger;
    private final ConfigService configService;
    private final ConcurrentHashMap<String, Integer> cachedPermissions;
    private final Pattern distancePermissionPattern;

    private volatile boolean luckPermsAvailable;
    private volatile LuckPerms luckPerms;

    @Inject
    public LuckPermsIntegrationService(Logger logger, ConfigService configService) {
        this.logger = logger;
        this.configService = configService;
        this.cachedPermissions = new ConcurrentHashMap<>();
        this.distancePermissionPattern = Pattern.compile("extendedhorizons\\.distance\\.(\\d+|unlimited)");

        initializeLuckPerms();
    }

    private void initializeLuckPerms() {
        try {
            if (Bukkit.getPluginManager().getPlugin("LuckPerms") != null) {
                this.luckPerms = LuckPermsProvider.get();
                this.luckPermsAvailable = true;
                logger.info("LuckPerms integration enabled");
            } else {
                this.luckPermsAvailable = false;
                logger.info("LuckPerms not found - using default permissions");
            }
        } catch (Exception e) {
            this.luckPermsAvailable = false;
            logger.warn("Failed to initialize LuckPerms integration: {}", e.getMessage());
        }
    }

    public int getMaxViewDistance(Player player) {
        if (!luckPermsAvailable || !configService.isLuckPermsEnabled()) {
            return getDefaultMaxDistance(player);
        }

        String cacheKey = player.getUniqueId().toString();

        // Check cache first
        Integer cached = cachedPermissions.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        // Calculate permission-based distance
        int maxDistance = calculatePermissionDistance(player);
        cachedPermissions.put(cacheKey, maxDistance);

        return maxDistance;
    }

    private int calculatePermissionDistance(Player player) {
        try {
            User user = luckPerms.getUserManager().getUser(player.getUniqueId());
            if (user == null) {
                return getDefaultMaxDistance(player);
            }

            int highestDistance = 0;
            boolean hasUnlimited = false;

            // Check user permissions
            for (Node node : user.getNodes()) {
                if (node.getKey().startsWith("extendedhorizons.distance.")) {
                    if (node.getKey().equals("extendedhorizons.distance.unlimited")) {
                        hasUnlimited = true;
                        break;
                    }

                    Matcher matcher = distancePermissionPattern.matcher(node.getKey());
                    if (matcher.matches()) {
                        try {
                            int distance = Integer.parseInt(matcher.group(1));
                            highestDistance = Math.max(highestDistance, distance);
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }

            // Check group permissions if enabled - use specific config method
            if (configService.isLuckPermsGroupPermissionsEnabled()) {
                for (Group group : user.getInheritedGroups(user.getQueryOptions())) {
                    if (group != null) {
                        for (Node node : group.getNodes()) {
                            if (node.getKey().startsWith("extendedhorizons.distance.")) {
                                if (node.getKey().equals("extendedhorizons.distance.unlimited")) {
                                    hasUnlimited = true;
                                    break;
                                }

                                Matcher matcher = distancePermissionPattern.matcher(node.getKey());
                                if (matcher.matches()) {
                                    try {
                                        int distance = Integer.parseInt(matcher.group(1));
                                        highestDistance = Math.max(highestDistance, distance);
                                    } catch (NumberFormatException ignored) {}
                                }
                            }
                        }
                    }
                    if (hasUnlimited) break;
                }
            }

            if (hasUnlimited) {
                return configService.getMaxViewDistance();
            }

            return Math.max(highestDistance, getDefaultMaxDistance(player));

        } catch (Exception e) {
            logger.error("Error calculating permission distance for player {}", player.getName(), e);
            return getDefaultMaxDistance(player);
        }
    }

    private int getDefaultMaxDistance(Player player) {
        // Fallback to Bukkit permissions
        if (player.hasPermission("extendedhorizons.distance.unlimited")) {
            return configService.getMaxViewDistance();
        }

        for (int distance = 64; distance >= 8; distance -= 8) {
            if (player.hasPermission("extendedhorizons.distance." + distance)) {
                return distance;
            }
        }

        return configService.getDefaultViewDistance();
    }

    public CompletableFuture<String> getPlayerPrimaryGroup(Player player) {
        if (!luckPermsAvailable) {
            return CompletableFuture.completedFuture("default");
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                User user = luckPerms.getUserManager().getUser(player.getUniqueId());
                if (user != null) {
                    return user.getPrimaryGroup();
                }
                return "default";
            } catch (Exception e) {
                logger.error("Error getting primary group for player {}", player.getName(), e);
                return "default";
            }
        });
    }

    public void invalidatePlayerCache(Player player) {
        cachedPermissions.remove(player.getUniqueId().toString());
    }

    public void invalidateAllCache() {
        cachedPermissions.clear();
        logger.debug("Cleared all LuckPerms permission cache");
    }

    public boolean hasPermission(Player player, String permission) {
        if (!luckPermsAvailable) {
            return player.hasPermission(permission);
        }

        try {
            User user = luckPerms.getUserManager().getUser(player.getUniqueId());
            if (user != null) {
                return user.getCachedData().getPermissionData().checkPermission(permission).asBoolean();
            }
        } catch (Exception e) {
            logger.error("Error checking permission {} for player {}", permission, player.getName(), e);
        }

        return player.hasPermission(permission);
    }

    public CompletableFuture<Boolean> updateUserPermission(Player player, String permission, boolean value) {
        if (!luckPermsAvailable) {
            return CompletableFuture.completedFuture(false);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                User user = luckPerms.getUserManager().getUser(player.getUniqueId());
                if (user != null) {
                    if (value) {
                        user.data().add(Node.builder(permission).build());
                    } else {
                        user.data().remove(Node.builder(permission).build());
                    }
                    luckPerms.getUserManager().saveUser(user);
                    invalidatePlayerCache(player);
                    return true;
                }
                return false;
            } catch (Exception e) {
                logger.error("Error updating permission {} for player {}", permission, player.getName(), e);
                return false;
            }
        });
    }

    public boolean isLuckPermsAvailable() {
        return luckPermsAvailable;
    }

    public int getCachedPermissionCount() {
        return cachedPermissions.size();
    }

    public PermissionStatistics getStatistics() {
        return new PermissionStatistics(
            luckPermsAvailable,
            cachedPermissions.size(),
            configService.isLuckPermsGroupPermissionsEnabled()
        );
    }

    public record PermissionStatistics(
        boolean luckPermsAvailable,
        int cachedPermissions,
        boolean useGroupPermissions
    ) {}
}
