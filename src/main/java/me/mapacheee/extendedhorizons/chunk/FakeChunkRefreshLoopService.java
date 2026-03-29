package me.mapacheee.extendedhorizons.chunk;

import com.thewinterframework.configurate.Container;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import me.mapacheee.extendedhorizons.chunk.cache.ChunkPacketCacheService;
import me.mapacheee.extendedhorizons.chunk.tracker.PlayerChunkTracker;
import me.mapacheee.extendedhorizons.config.Config;
import net.minecraft.world.level.ChunkPos;
import org.bukkit.World;
import org.bukkit.entity.Player;

public class FakeChunkRefreshLoopService {

  public interface Actions {
    void refreshChunkForPlayer(
        Player player,
        World world,
        PlayerChunkTracker tracker,
        int chunkX,
        int chunkZ,
        long chunkKey);

    void handleRealChunkInteraction(World world, int chunkX, int chunkZ);
  }

  private final Container<Config> configContainer;
  private final FakeChunkRefreshCoordinator refreshCoordinator;
  private final ChunkPacketCacheService chunkPacketCacheService;
  private final Map<UUID, Long> lastAutoRefreshMs = new ConcurrentHashMap<>();
  private final Map<UUID, AtomicInteger> autoRefreshCursor = new ConcurrentHashMap<>();

  public FakeChunkRefreshLoopService(
      Container<Config> configContainer,
      FakeChunkRefreshCoordinator refreshCoordinator,
      ChunkPacketCacheService chunkPacketCacheService) {
    this.configContainer = configContainer;
    this.refreshCoordinator = refreshCoordinator;
    this.chunkPacketCacheService = chunkPacketCacheService;
  }

  public void clearAll() {
    lastAutoRefreshMs.clear();
    autoRefreshCursor.clear();
  }

  public void clearPlayer(UUID playerId) {
    if (playerId == null) return;
    lastAutoRefreshMs.remove(playerId);
    autoRefreshCursor.remove(playerId);
  }

  public void tick(
      Player player, World world, UUID playerId, PlayerChunkTracker tracker, Actions actions) {
    if (!config().autoRefreshEnabled()) return;
    if (player == null || world == null || playerId == null || tracker == null || actions == null)
      return;
    Set<Long> sent = tracker.getSentChunks();
    if (sent.isEmpty()) return;
    long now = System.currentTimeMillis();
    long periodMs = config().autoRefreshPeriodMs();
    Long last = lastAutoRefreshMs.get(playerId);
    if (last != null && now - last < periodMs) return;

    List<Long> sentList = new ArrayList<>(sent);
    int size = sentList.size();
    if (size == 0) return;

    int perCycle = Math.min(config().autoRefreshChunksPerCycle(), size);
    UUID worldId = world.getUID();
    int refreshed = 0;
    long dirtyTtlMs = Math.max(3000L, periodMs * 10L);
    Set<Long> refreshedKeys = new HashSet<>();

    for (Long chunkKey : sentList) {
      if (refreshed >= perCycle) break;
      Long dirtySince = refreshCoordinator.getDirtySince(worldId, chunkKey);
      if (dirtySince == null) continue;
      if (now - dirtySince > dirtyTtlMs) {
        refreshCoordinator.removeDirty(worldId, chunkKey);
        continue;
      }
      int cx = ChunkPos.getX(chunkKey);
      int cz = ChunkPos.getZ(chunkKey);
      actions.refreshChunkForPlayer(player, world, tracker, cx, cz, chunkKey);
      refreshedKeys.add(chunkKey);
      refreshed++;
    }

    for (Long chunkKey : sentList) {
      if (refreshed >= perCycle) break;
      if (refreshedKeys.contains(chunkKey)) continue;
      if (!chunkPacketCacheService.hasRealPlayers(worldId, chunkKey)) continue;
      int cx = ChunkPos.getX(chunkKey);
      int cz = ChunkPos.getZ(chunkKey);
      actions.refreshChunkForPlayer(player, world, tracker, cx, cz, chunkKey);
      refreshedKeys.add(chunkKey);
      refreshed++;
    }

    boolean fallbackEnabled = config().autoRefreshInvalidateFallbackEnabled();
    int fallbackCap = config().autoRefreshInvalidateFallbackMaxPerCycle();
    if (fallbackEnabled && fallbackCap > 0 && refreshed < perCycle) {
      AtomicInteger cursor = autoRefreshCursor.computeIfAbsent(playerId, k -> new AtomicInteger(0));
      int remaining = Math.min(perCycle - refreshed, fallbackCap);
      for (int i = 0; i < remaining; i++) {
        int idx = Math.floorMod(cursor.getAndIncrement(), size);
        long chunkKey = sentList.get(idx);
        if (refreshedKeys.contains(chunkKey)) continue;
        int cx = ChunkPos.getX(chunkKey);
        int cz = ChunkPos.getZ(chunkKey);
        actions.handleRealChunkInteraction(world, cx, cz);
      }
    }
    lastAutoRefreshMs.put(playerId, now);
  }

  private Config config() {
    Config cfg = configContainer.get();
    return cfg == null ? Config.empty() : cfg;
  }
}
