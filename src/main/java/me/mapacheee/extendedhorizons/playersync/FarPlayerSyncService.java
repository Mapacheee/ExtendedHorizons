package me.mapacheee.extendedhorizons.playersync;

import com.google.inject.Inject;
import com.thewinterframework.configurate.Container;
import com.thewinterframework.service.annotation.Service;
import com.thewinterframework.service.annotation.lifecycle.OnDisable;
import com.thewinterframework.service.annotation.lifecycle.OnEnable;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import me.mapacheee.extendedhorizons.config.Config;
import me.mapacheee.extendedhorizons.playersync.packet.FarPlayerPacketAdapter;
import me.mapacheee.extendedhorizons.playersync.packet.FarPlayerPacketAdapterFactory;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

@Service
public class FarPlayerSyncService {

  private final Container<Config> configContainer;
  private final FarPlayerPacketAdapter packetAdapter = FarPlayerPacketAdapterFactory.create();
  private final Map<UUID, Map<UUID, MirrorState>> shownByViewer = new ConcurrentHashMap<>();
  private final Map<UUID, Long> swingSequence = new ConcurrentHashMap<>();
  private volatile ScheduledTask syncTask;

  @Inject
  public FarPlayerSyncService(Container<Config> configContainer) {
    this.configContainer = configContainer;
  }

  @OnEnable
  public void onEnable() {
    startLoop();
  }

  @OnDisable
  public void onDisable() {
    stopLoop();
    shownByViewer.clear();
    swingSequence.clear();
  }

  public void recordSwing(UUID playerId) {
    if (playerId == null) return;
    swingSequence.merge(playerId, 1L, Long::sum);
  }

  private void startLoop() {
    runGlobal(
        () -> {
          stopLoop();
          var plugin = ExtendedHorizonsPlugin.getInstance();
          if (plugin == null || !plugin.isEnabled()) return;
          int period = Math.max(1, config().farPlayersUpdateIntervalTicks());
          syncTask =
              Bukkit.getServer()
                  .getGlobalRegionScheduler()
                  .runAtFixedRate(plugin, task -> tick(), period, period);
        });
  }

  private void stopLoop() {
    ScheduledTask current = syncTask;
    syncTask = null;
    if (current != null) {
      try {
        current.cancel();
      } catch (Throwable ignored) {
      }
    }
  }

  private void tick() {
    if (!config().farPlayersEnabled()) {
      clearAll();
      return;
    }
    if (!packetAdapter.isReady()) return;

    int maxDistanceChunks = config().farPlayersMaxDistanceChunks();
    int maxDistanceSq = maxDistanceChunks * maxDistanceChunks;
    int maxSyncedPerViewer = config().farPlayersMaxSyncedPerViewer();
    int spawnBurstPerTick = config().farPlayersSpawnBurstPerTick();
    int globalView = Math.max(2, Bukkit.getServer().getViewDistance());
    boolean respectVanish = config().farPlayersRespectVanish();
    boolean disableInSpectator = config().farPlayersDisableInSpectator();
    boolean requireViewerPermission = config().farPlayersRequireViewerPermission();
    String viewerPermissionNode = config().farPlayersViewerPermissionNode();
    boolean allowMountSync = config().farPlayersAllowMountSync();

    List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
    Set<UUID> onlineIds = new HashSet<>();
    for (Player player : online) {
      onlineIds.add(player.getUniqueId());
    }

    for (Player viewer : online) {
      if (viewer == null || !viewer.isOnline()) continue;
      UUID viewerId = viewer.getUniqueId();
      Map<UUID, MirrorState> mirrors =
          shownByViewer.computeIfAbsent(viewerId, key -> new ConcurrentHashMap<>());
      Set<UUID> keep = new HashSet<>();
      int synced = 0;
      int spawnedThisTick = 0;
      int vanillaDistance = effectiveVanillaViewDistance(viewer, globalView);
      int vanillaSq = (vanillaDistance + 1) * (vanillaDistance + 1);
      World viewerWorld = viewer.getWorld();
      if (viewerWorld == null) {
        mirrors.clear();
        continue;
      }
      if (isViewerBlocked(
          viewer,
          viewerWorld,
          disableInSpectator,
          requireViewerPermission,
          viewerPermissionNode)) {
        for (MirrorState state : new ArrayList<>(mirrors.values())) {
          if (state.mountMirror() != null) {
            packetAdapter.clearMount(viewer, state.mountMirror());
          }
          packetAdapter.despawn(viewer, state.targetId(), state.entityId());
        }
        shownByViewer.remove(viewerId);
        continue;
      }

      for (Player target : online) {
        if (target == null || !target.isOnline()) continue;
        if (target.getUniqueId().equals(viewerId)) continue;
        if (target.getWorld() == null || !target.getWorld().getUID().equals(viewerWorld.getUID())) {
          continue;
        }
        if (isTargetBlocked(target, viewer, viewerWorld, disableInSpectator, respectVanish)) {
          continue;
        }

        int dx = (target.getLocation().getBlockX() >> 4) - (viewer.getLocation().getBlockX() >> 4);
        int dz = (target.getLocation().getBlockZ() >> 4) - (viewer.getLocation().getBlockZ() >> 4);
        int distSq = dx * dx + dz * dz;
        if (distSq > maxDistanceSq) continue;
        UUID targetId = target.getUniqueId();
        MirrorState state = mirrors.get(targetId);
        if (distSq <= vanillaSq) {
          if (state != null) {
            keep.add(targetId);
            long currentSwing = currentSwing(targetId);
            boolean swingChanged = state.swingSequence() != currentSwing;
            if (state.needsUpdate(target)) {
              if (packetAdapter.update(viewer, target)) {
                var mountMirror = syncMountIfEnabled(allowMountSync, viewer, target, state.mountMirror());
                mirrors.put(targetId, state.capture(target, currentSwing, mountMirror));
              } else {
                mirrors.remove(targetId);
              }
            } else if (swingChanged) {
              if (packetAdapter.animateSwing(viewer, target)) {
                var mountMirror = syncMountIfEnabled(allowMountSync, viewer, target, state.mountMirror());
                mirrors.put(targetId, state.capture(target, currentSwing, mountMirror));
              } else {
                mirrors.remove(targetId);
              }
            } else if (allowMountSync) {
              var mountMirror = syncMountIfEnabled(true, viewer, target, state.mountMirror());
              if (!Objects.equals(mountMirror, state.mountMirror())) {
                mirrors.put(targetId, state.capture(target, currentSwing, mountMirror));
              }
            }
          }
          continue;
        }

        if (synced >= maxSyncedPerViewer) continue;
        synced++;
        keep.add(targetId);
        if (state == null) {
          if (spawnedThisTick >= spawnBurstPerTick) continue;
          if (packetAdapter.spawn(viewer, target)) {
            var mountMirror = syncMountIfEnabled(allowMountSync, viewer, target, null);
            mirrors.put(targetId, MirrorState.from(target, currentSwing(targetId), mountMirror));
            spawnedThisTick++;
          }
          continue;
        }

        long currentSwing = currentSwing(targetId);
        boolean swingChanged = state.swingSequence() != currentSwing;
        if (state.needsUpdate(target)) {
          if (packetAdapter.update(viewer, target)) {
            var mountMirror = syncMountIfEnabled(allowMountSync, viewer, target, state.mountMirror());
            mirrors.put(targetId, state.capture(target, currentSwing, mountMirror));
          } else {
            mirrors.remove(targetId);
          }
        } else if (swingChanged) {
          if (packetAdapter.animateSwing(viewer, target)) {
            var mountMirror = syncMountIfEnabled(allowMountSync, viewer, target, state.mountMirror());
            mirrors.put(targetId, state.capture(target, currentSwing, mountMirror));
          } else {
            mirrors.remove(targetId);
          }
        } else if (allowMountSync) {
          var mountMirror = syncMountIfEnabled(true, viewer, target, state.mountMirror());
          if (!Objects.equals(mountMirror, state.mountMirror())) {
            mirrors.put(targetId, state.capture(target, currentSwing, mountMirror));
          }
        }
      }

      for (Map.Entry<UUID, MirrorState> entry : new ArrayList<>(mirrors.entrySet())) {
        UUID targetId = entry.getKey();
        if (!keep.contains(targetId) || !onlineIds.contains(targetId)) {
          if (entry.getValue().mountMirror() != null) {
            packetAdapter.clearMount(viewer, entry.getValue().mountMirror());
          }
          packetAdapter.despawn(viewer, entry.getValue().targetId(), entry.getValue().entityId());
          mirrors.remove(targetId);
        }
      }

      if (mirrors.isEmpty()) {
        shownByViewer.remove(viewerId);
      }
    }

    for (UUID viewerId : new ArrayList<>(shownByViewer.keySet())) {
      if (!onlineIds.contains(viewerId)) {
        shownByViewer.remove(viewerId);
      }
    }
  }

  private int effectiveVanillaViewDistance(Player player, int globalView) {
    int playerView = globalView;
    try {
      int apiView = player.getViewDistance();
      if (apiView > 0) playerView = Math.min(globalView, apiView);
    } catch (Throwable ignored) {
    }
    if (playerView < 2) playerView = 2;
    return playerView;
  }

  private void clearAll() {
    if (shownByViewer.isEmpty()) return;
    for (Player viewer : Bukkit.getOnlinePlayers()) {
      Map<UUID, MirrorState> mirrors = shownByViewer.get(viewer.getUniqueId());
      if (mirrors == null) continue;
      for (MirrorState state : mirrors.values()) {
        if (state.mountMirror() != null) {
          packetAdapter.clearMount(viewer, state.mountMirror());
        }
        packetAdapter.despawn(viewer, state.targetId(), state.entityId());
      }
    }
    shownByViewer.clear();
  }

  private void runGlobal(Runnable runnable) {
    if (runnable == null) return;
    var plugin = ExtendedHorizonsPlugin.getInstance();
    if (plugin == null || !plugin.isEnabled()) return;
    try {
      Bukkit.getServer().getGlobalRegionScheduler().execute(plugin, runnable);
    } catch (Throwable ignored) {
    }
  }

  private Config config() {
    Config cfg = configContainer.get();
    return cfg == null ? Config.empty() : cfg;
  }

  private long currentSwing(UUID playerId) {
    if (playerId == null) return 0L;
    return swingSequence.getOrDefault(playerId, 0L);
  }

  private boolean isViewerBlocked(
      Player viewer,
      World viewerWorld,
      boolean disableInSpectator,
      boolean requireViewerPermission,
      String viewerPermissionNode) {
    if (viewer == null || viewerWorld == null) return true;
    if (!config().farPlayersEnabledForWorld(viewerWorld.getName())) return true;
    if (disableInSpectator && viewer.getGameMode() == GameMode.SPECTATOR) return true;
    return requireViewerPermission && (viewerPermissionNode == null || !viewer.hasPermission(viewerPermissionNode));
  }

  private boolean isTargetBlocked(
      Player target,
      Player viewer,
      World viewerWorld,
      boolean disableInSpectator,
      boolean respectVanish) {
    if (target == null || viewer == null || viewerWorld == null) return true;
    if (target.getWorld() == null || !target.getWorld().getUID().equals(viewerWorld.getUID())) return true;
    if (!config().farPlayersEnabledForWorld(target.getWorld().getName())) return true;
    if (disableInSpectator && target.getGameMode() == GameMode.SPECTATOR) return true;
    return respectVanish && !viewer.canSee(target);
  }

  private FarPlayerPacketAdapter.MountMirror syncMountIfEnabled(
      boolean allowMountSync,
      Player viewer,
      Player target,
      FarPlayerPacketAdapter.MountMirror current) {
    if (!allowMountSync) {
      if (current != null) packetAdapter.clearMount(viewer, current);
      return null;
    }
    return packetAdapter.syncMount(viewer, target, current);
  }

  public record MirrorState(
      UUID targetId,
      int entityId,
      double x,
      double y,
      double z,
      float yaw,
      float pitch,
      int equipmentSignature,
      int metadataSignature,
      long swingSequence,
      FarPlayerPacketAdapter.MountMirror mountMirror
  ) {

    static MirrorState from(
        Player target,
        long swingSequence,
        FarPlayerPacketAdapter.MountMirror mountMirror) {
      return new MirrorState(
          target.getUniqueId(),
          target.getEntityId(),
          target.getLocation().getX(),
          target.getLocation().getY(),
          target.getLocation().getZ(),
          target.getLocation().getYaw(),
          target.getLocation().getPitch(),
          equipmentSignature(target),
          metadataSignature(target),
          swingSequence,
          mountMirror);
    }

    boolean needsUpdate(Player target) {
      double nx = target.getLocation().getX();
      double ny = target.getLocation().getY();
      double nz = target.getLocation().getZ();
      float nyaw = target.getLocation().getYaw();
      float npitch = target.getLocation().getPitch();
      if (Math.abs(nx - x) > 0.05d) return true;
      if (Math.abs(ny - y) > 0.05d) return true;
      if (Math.abs(nz - z) > 0.05d) return true;
      if (Math.abs(nyaw - yaw) > 1.5f) return true;
      if (Math.abs(npitch - pitch) > 1.5f) return true;
      if (equipmentSignature(target) != equipmentSignature) return true;
      return metadataSignature(target) != metadataSignature;
    }

    MirrorState capture(
        Player target,
        long swingSequence,
        FarPlayerPacketAdapter.MountMirror mountMirror) {
      return from(target, swingSequence, mountMirror);
    }

    private static int equipmentSignature(Player target) {
      if (target == null) return 0;
      var inv = target.getInventory();
      if (inv == null) return 0;
      int result = 17;
      result = 31 * result + itemSignature(inv.getItemInMainHand());
      result = 31 * result + itemSignature(inv.getItemInOffHand());
      result = 31 * result + itemSignature(inv.getHelmet());
      result = 31 * result + itemSignature(inv.getChestplate());
      result = 31 * result + itemSignature(inv.getLeggings());
      result = 31 * result + itemSignature(inv.getBoots());
      return result;
    }

    private static int itemSignature(ItemStack item) {
      if (item == null || item.getType().isAir()) return 0;
      int result = 17;
      result = 31 * result + item.getType().ordinal();
      result = 31 * result + item.getAmount();
      if (item.hasItemMeta() && item.getItemMeta() != null) {
        result = 31 * result + item.getItemMeta().hashCode();
      }
      return result;
    }

    private static int metadataSignature(Player target) {
      if (target == null) return 0;
      int result = 17;
      result = 31 * result + Boolean.hashCode(target.isSneaking());
      result = 31 * result + Boolean.hashCode(target.isSprinting());
      result = 31 * result + Boolean.hashCode(target.isGliding());
      result = 31 * result + Boolean.hashCode(target.isSwimming());
      result = 31 * result + Boolean.hashCode(target.isInvisible());
      Object visualFire = target.getVisualFire();
      result = 31 * result + (visualFire == null ? 0 : visualFire.hashCode());
      result = 31 * result + Boolean.hashCode(target.isSleeping());
      result = 31 * result + Boolean.hashCode(target.isBlocking());
      result = 31 * result + target.getPose().ordinal();
      if (target.getVehicle() != null && target.getVehicle().getUniqueId() != null) {
        result = 31 * result + target.getVehicle().getUniqueId().hashCode();
      }
      return result;
    }
  }
}
