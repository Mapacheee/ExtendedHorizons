package me.mapacheee.extendedhorizons.fakechunks.dispatch;

import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;

import java.util.concurrent.CompletableFuture;

public record ChunkSendQueueEntry(long chunkKey, CompletableFuture<ByteBuf> buildFuture, long queuedAtNanos) {

    public ChunkSendQueueEntry(long chunkKey, CompletableFuture<ByteBuf> buildFuture) {
        this(chunkKey, buildFuture, System.nanoTime());
    }

    public void releaseFuture() {
        this.buildFuture.thenAccept(buf -> {
            if (buf != null && buf.refCnt() > 0) {
                ReferenceCountUtil.release(buf);
            }
        });
    }
}
