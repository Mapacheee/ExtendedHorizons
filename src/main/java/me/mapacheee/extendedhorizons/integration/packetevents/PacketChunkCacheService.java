package me.mapacheee.extendedhorizons.integration.packetevents;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.thewinterframework.service.annotation.Service;
import com.thewinterframework.service.annotation.lifecycle.OnEnable;
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

/*
 *   LRU cache for PacketEvents Column objects intercepted from server chunk packets
 *   Stores complete chunk data with lighting for reuse as fake chunks
 *   Implements automatic TTL-based expiration and size-based eviction
 */
@Service
public class PacketChunkCacheService {

    private static final int DEFAULT_MAX_ENTRIES = 512;
    private static final long DEFAULT_TTL_MILLIS = 300_000L;

    private final Plugin plugin = JavaPlugin.getPlugin(ExtendedHorizonsPlugin.class);
    private final int maxEntries = DEFAULT_MAX_ENTRIES;
    private final long ttlMillis = DEFAULT_TTL_MILLIS;

    private static final class Entry {
        final Column column;
        long lastAccess;
        Entry(Column c) { this.column = c; this.lastAccess = System.currentTimeMillis(); }
    }

    private final Map<Long, Entry> cache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Entry> eldest) {
            return size() > maxEntries;
        }
    });

    @OnEnable
    public void register() {
        PacketEvents.getAPI().getEventManager().registerListener(new PacketListenerAbstract(PacketListenerPriority.MONITOR) {
            @Override
            public void onPacketSend(PacketSendEvent event) {
                if (event.getPacketType() != PacketType.Play.Server.CHUNK_DATA) return;

                try {
                    WrapperPlayServerChunkData wrapper = new WrapperPlayServerChunkData(event);
                    Column column = wrapper.getColumn();
                    if (column == null) return;

                    long key = getKey(column.getX(), column.getZ());
                    cache.put(key, new Entry(column));
                } catch (Throwable e) {
                    // Silently ignore packet processing errors to avoid spam
                    // These are typically version compatibility issues or malformed packets
                    // Logging would create excessive noise in production
                }
            }
        });

        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            long now = System.currentTimeMillis();
            synchronized (cache) {
                cache.entrySet().removeIf(e -> now - e.getValue().lastAccess > ttlMillis);
            }
        }, 300L, 300L);
    }

    public Column get(int x, int z) {
        long key = getKey(x, z);
        Entry e = cache.get(key);
        if (e == null) return null;
        long now = System.currentTimeMillis();
        if (now - e.lastAccess > ttlMillis) {
            cache.remove(key);
            return null;
        }
        e.lastAccess = now;
        return e.column;
    }

    public void put(int x, int z, Column column) {
        long key = getKey(x, z);
        cache.put(key, new Entry(column));
    }

    public int size() {
        return cache.size();
    }

    public void clear() {
        synchronized (cache) {
            cache.clear();
        }
    }

    private static long getKey(int x, int z) {
        return (long) x & 0xFFFFFFFFL | ((long) z & 0xFFFFFFFFL) << 32;
    }
}
