package me.mapacheee.extendedhorizons.fakechunks.netty;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runtime packet-id registry to avoid hardcoded protocol ids across versions.
 */
public final class PacketIdRegistry {

    private static final int UNRESOLVED = -1;
    private static final AtomicInteger LEVEL_CHUNK_WITH_LIGHT_ID = new AtomicInteger(UNRESOLVED);
    private static final AttributeKey<Boolean> PENDING_LEVEL_CHUNK_PROBE = AttributeKey.valueOf("eh_pending_level_chunk_probe");

    private PacketIdRegistry() {}

    public static boolean hasLevelChunkWithLightId() {
        return LEVEL_CHUNK_WITH_LIGHT_ID.get() != UNRESOLVED;
    }

    public static int getLevelChunkWithLightId() {
        return LEVEL_CHUNK_WITH_LIGHT_ID.get();
    }

    public static void resolveLevelChunkWithLightId(int packetId) {
        if (packetId < 0) {
            return;
        }
        LEVEL_CHUNK_WITH_LIGHT_ID.compareAndSet(UNRESOLVED, packetId);
    }

    public static void markPendingLevelChunkProbe(Channel channel) {
        if (channel == null || hasLevelChunkWithLightId()) {
            return;
        }
        channel.attr(PENDING_LEVEL_CHUNK_PROBE).set(Boolean.TRUE);
    }

    public static boolean consumePendingLevelChunkProbe(Channel channel) {
        if (channel == null) {
            return false;
        }
        Boolean pending = channel.attr(PENDING_LEVEL_CHUNK_PROBE).get();
        if (Boolean.TRUE.equals(pending)) {
            channel.attr(PENDING_LEVEL_CHUNK_PROBE).set(Boolean.FALSE);
            return true;
        }
        return false;
    }
}


