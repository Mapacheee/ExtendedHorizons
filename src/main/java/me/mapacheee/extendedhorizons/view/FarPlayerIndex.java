package me.mapacheee.extendedhorizons.view;

import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.world.level.ChunkPos;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class FarPlayerIndex {

  private static final int CELL_SHIFT = 3;
  private static final Comparator<Candidate> NEAREST = Comparator.comparingLong(Candidate::distance)
    .thenComparingInt(candidate -> candidate.snapshot().id());
  private final ConcurrentHashMap<Cell, Set<FarPlayerState>> cells = new ConcurrentHashMap<>();

  Cell cell(World world, int x, int z) {
    return new Cell(world, x >> CELL_SHIFT, z >> CELL_SHIFT);
  }

  void move(FarPlayerState player, Cell previous, Cell next) {
    if (previous != null) {
      this.cells.computeIfPresent(previous, (key, players) -> {
        players.remove(player);
        return players.isEmpty() ? null : players;
      });
    }
    if (next != null) {
      this.cells.compute(next, (key, players) -> {
        if (players == null) {
          players = ConcurrentHashMap.newKeySet();
        }
        players.add(player);
        return players;
      });
    }
  }

  List<FarPlayerState.Snapshot> candidates(Player viewer, ViewSnapshot view, ChunkStream stream, int limit) {
    if (!view.enabled() || this.cells.isEmpty()) {
      return List.of();
    }
    var nearest = new PriorityQueue<>(limit, NEAREST.reversed());
    int radius = view.distance();
    for (int x = (view.x() - radius) >> CELL_SHIFT; x <= (view.x() + radius) >> CELL_SHIFT; x++) {
      for (int z = (view.z() - radius) >> CELL_SHIFT; z <= (view.z() + radius) >> CELL_SHIFT; z++) {
        var players = this.cells.get(new Cell(view.world(), x, z));
        if (players == null) {
          continue;
        }
        for (FarPlayerState state : players) {
          var target = state.snapshot();
          if (target == null || target.world() != view.world() || state.player() == viewer
            || (ChunkPos.getX(target.chunk()) >> CELL_SHIFT) != x
            || (ChunkPos.getZ(target.chunk()) >> CELL_SHIFT) != z) {
            continue;
          }
          int targetX = ChunkPos.getX(target.chunk());
          int targetZ = ChunkPos.getZ(target.chunk());
          if (!ChunkTrackingView.isWithinDistance(view.x(), view.z(), radius, targetX, targetZ, false)
            || stream.tracksNativePlayer(target.nativeId()) || !viewer.canSee(state.player())) {
            continue;
          }
          long dx = targetX - (long) view.x();
          long dz = targetZ - (long) view.z();
          Candidate candidate = new Candidate(target, dx * dx + dz * dz);
          if (nearest.size() < limit) {
            nearest.add(candidate);
          } else if (NEAREST.compare(candidate, nearest.element()) < 0) {
            nearest.remove();
            nearest.add(candidate);
          }
        }
      }
    }
    if (nearest.isEmpty()) {
      return List.of();
    }
    var sorted = new ArrayList<>(nearest);
    sorted.sort(NEAREST);
    return sorted.stream().map(Candidate::snapshot).toList();
  }

  record Cell(World world, int x, int z) {}

  private record Candidate(FarPlayerState.Snapshot snapshot, long distance) {}
}
