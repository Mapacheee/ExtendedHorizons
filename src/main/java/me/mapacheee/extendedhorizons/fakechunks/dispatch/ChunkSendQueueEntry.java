package me.mapacheee.extendedhorizons.fakechunks.dispatch;

import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class ChunkSendQueueEntry {

    private final long chunkKey;
    private final UUID worldId;
    private final long sessionEpoch;
    private final long cacheGeneration;
    private final CompletableFuture<ByteBuf> buildFuture;
    private final long queuedAtNanos;
    private boolean released;

    public ChunkSendQueueEntry(
        long chunkKey,
        UUID worldId,
        long sessionEpoch,
        long cacheGeneration,
        CompletableFuture<ByteBuf> buildFuture
    ) {
        this.chunkKey = chunkKey;
        this.worldId = worldId;
        this.sessionEpoch = sessionEpoch;
        this.cacheGeneration = cacheGeneration;
        this.buildFuture = buildFuture;
        this.queuedAtNanos = System.nanoTime();
    }

    public long chunkKey() {
        return this.chunkKey;
    }

    public CompletableFuture<ByteBuf> buildFuture() {
        return this.buildFuture;
    }

    public UUID worldId() {
        return this.worldId;
    }

    public long sessionEpoch() {
        return this.sessionEpoch;
    }

    public long cacheGeneration() {
        return this.cacheGeneration;
    }

    public long queuedAtNanos() {
        return this.queuedAtNanos;
    }

    /**
     * Acquires a temporary owned view while this entry still owns its future result.
     */
    public synchronized ByteBuf acquirePayload() {
        if (this.released || !this.buildFuture.isDone() || this.buildFuture.isCompletedExceptionally()) {
            return null;
        }
        ByteBuf payload = this.buildFuture.getNow(null);
        if (payload == null || !payload.isReadable()) {
            return null;
        }
        return payload.retainedDuplicate();
    }

    public void releaseFuture() {
        synchronized (this) {
            if (this.released) {
                return;
            }
            this.released = true;
        }
        if (this.buildFuture.cancel(false)) {
            return;
        }
        this.buildFuture.whenComplete((payload, throwable) -> {
            if (throwable == null) {
                ReferenceCountUtil.release(payload);
            }
        });
    }
}
