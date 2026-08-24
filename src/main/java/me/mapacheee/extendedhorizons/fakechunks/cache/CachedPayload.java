package me.mapacheee.extendedhorizons.fakechunks.cache;

import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;

/**
 * Owns exactly one reference and serializes acquisition with release of that reference.
 */
final class CachedPayload implements AutoCloseable {

    private ByteBuf payload;

    private CachedPayload(ByteBuf payload) {
        this.payload = payload;
    }

    static CachedPayload retain(ByteBuf source) {
        if (!isReadable(source)) {
            return null;
        }
        ByteBuf retained = null;
        try {
            retained = source.retainedDuplicate();
            if (!retained.isReadable()) {
                release(retained);
                return null;
            }
            return new CachedPayload(retained);
        } catch (RuntimeException ignored) {
            release(retained);
            return null;
        }
    }

    static CachedPayload takeOwnership(ByteBuf payload) {
        return payload == null ? null : new CachedPayload(payload);
    }

    synchronized ByteBuf acquire() {
        ByteBuf current = this.payload;
        if (!isReadable(current)) {
            return null;
        }
        try {
            return current.retainedDuplicate();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    synchronized boolean isReadable() {
        return isReadable(this.payload);
    }

    @Override
    public synchronized void close() {
        ByteBuf current = this.payload;
        this.payload = null;
        release(current);
    }

    static void release(ByteBuf payload) {
        if (payload != null) {
            ReferenceCountUtil.release(payload);
        }
    }

    private static boolean isReadable(ByteBuf payload) {
        if (payload == null) {
            return false;
        }
        try {
            return payload.isReadable();
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
