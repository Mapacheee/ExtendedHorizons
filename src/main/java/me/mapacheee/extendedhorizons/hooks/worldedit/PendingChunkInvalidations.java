package me.mapacheee.extendedhorizons.hooks.worldedit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Owns pending edits until they are atomically transferred to a bounded processing batch. */
final class PendingChunkInvalidations {
    private static final long SETTLE_NANOS = 100_000_000L;
    private final LinkedHashMap<Key, Long> pending = new LinkedHashMap<>();

    synchronized void offer(UUID world, long chunk, long now) {
        if (world == null) return;
        Key key = new Key(world, chunk);
        // Keep insertion order aligned with deadlines, including repeated edits.
        pending.remove(key);
        pending.put(key, now + SETTLE_NANOS);
    }

    synchronized Map<UUID, List<Long>> drain(long now, int limit) {
        Map<UUID, List<Long>> result = new LinkedHashMap<>();
        var iterator = pending.entrySet().iterator();
        while (limit > 0 && iterator.hasNext()) {
            var entry = iterator.next();
            if (now - entry.getValue() < 0L) break;
            result.computeIfAbsent(entry.getKey().world(), ignored -> new ArrayList<>()).add(entry.getKey().chunk());
            iterator.remove();
            limit--;
        }
        return result;
    }

    synchronized void clear() { pending.clear(); }
    private record Key(UUID world, long chunk) {}
}
