package me.mapacheee.extendedhorizons.fakechunks.dispatch;

import java.util.concurrent.CompletableFuture;

public record ChunkSendQueueEntry(long chunkKey, CompletableFuture<Boolean> sendFuture) {
}

