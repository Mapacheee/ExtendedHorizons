package me.mapacheee.extendedhorizons.fakechunks.dispatch;

import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ChunkSendQueueEntry {

    private final long chunkKey;
    private final CompletableFuture<ByteBuf> buildFuture;
    private final long queuedAtNanos;
    private final AtomicBoolean released = new AtomicBoolean();

    public ChunkSendQueueEntry(long chunkKey, CompletableFuture<ByteBuf> buildFuture) {
        this.chunkKey = chunkKey;
        this.buildFuture = buildFuture;
        this.queuedAtNanos = System.nanoTime();
    }

    public long chunkKey() {
        return this.chunkKey;
    }

    public CompletableFuture<ByteBuf> buildFuture() {
        return this.buildFuture;
    }

    public long queuedAtNanos() {
        return this.queuedAtNanos;
    }

    public void releaseFuture() {
        if (this.released.compareAndSet(false, true)) {
            if (this.buildFuture.isDone() && !this.buildFuture.isCompletedExceptionally()) {
                ByteBuf buf = this.buildFuture.getNow(null);
                if (buf != null && buf.refCnt() > 0) {
                    ReferenceCountUtil.release(buf);
                }
            } else if (!this.buildFuture.isDone()) {
                this.buildFuture.thenAccept(buf -> {
                    if (buf != null && buf.refCnt() > 0) {
                        ReferenceCountUtil.release(buf);
                    }
                });
            }
        }
    }
}
