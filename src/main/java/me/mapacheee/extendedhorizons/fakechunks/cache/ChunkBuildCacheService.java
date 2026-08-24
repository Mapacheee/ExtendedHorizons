package me.mapacheee.extendedhorizons.fakechunks.cache;

import com.google.inject.Inject;
import com.thewinterframework.configurate.Container;
import com.thewinterframework.service.annotation.Service;
import com.thewinterframework.service.annotation.lifecycle.OnDisable;
import io.netty.buffer.ByteBuf;
import me.mapacheee.extendedhorizons.config.EhConfig;
import me.mapacheee.lib.caffeine.cache.Cache;
import me.mapacheee.lib.caffeine.cache.Caffeine;
import me.mapacheee.lib.caffeine.cache.RemovalCause;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

@Service
public final class ChunkBuildCacheService {

    private final Container<EhConfig> configContainer;
    private final Object lifecycleLock = new Object();
    private final AtomicLong generationId = new AtomicLong();
    private volatile CacheGeneration generation;

    @Inject
    public ChunkBuildCacheService(Container<EhConfig> configContainer) {
        this.configContainer = configContainer;
        this.rebuildCaches();
    }

    public void rebuildCaches() {
        int ttlSeconds = Math.max(1, this.configContainer.get().cacheTtlSeconds());
        int maxEntries = Math.max(1, this.configContainer.get().cacheMaxEntries());
        long bypassMs = Math.max(1L, this.configContainer.get().cacheBypassAfterRealInteractionMs());
        long retryMs = Math.max(1L, this.configContainer.get().unavailableRetryMs());
        long unavailableTtlMs = retryMs > Long.MAX_VALUE / 2L
            ? Long.MAX_VALUE
            : Math.max(1_000L, retryMs * 2L);

        CacheGeneration next = new CacheGeneration(ttlSeconds, maxEntries, bypassMs, unavailableTtlMs);
        CacheGeneration previous;
        synchronized (this.lifecycleLock) {
            previous = this.generation;
            if (previous != null) {
                previous.retire();
            }
            this.generation = next;
            this.generationId.incrementAndGet();
        }
        if (previous != null) {
            previous.close();
        }
    }

    /** Returns one owned reference that the caller must release, or {@code null}. */
    public ByteBuf getSerialized(UUID worldId, long chunkKey) {
        if (worldId == null) {
            return null;
        }
        CacheGeneration current = this.generation;
        return current == null ? null : current.acquireSerialized(new ChunkKey(worldId, chunkKey));
    }

    public long generation() {
        return this.generationId.get();
    }

    public boolean available() {
        return this.generation != null;
    }

    public void suspendForReload() {
        CacheGeneration previous;
        synchronized (this.lifecycleLock) {
            previous = this.generation;
            if (previous != null) {
                previous.retire();
            }
            this.generation = null;
            this.generationId.incrementAndGet();
        }
        if (previous != null) {
            previous.close();
        }
    }

    /**
     * Every returned future is independent. A non-null result is one owned reference
     * transferred to that future's consumer and must be released exactly once. The
     * starter transfers ownership of its non-null completion payload to this service.
     * A null, exceptional, or cancelled result transfers no reference.
     */
    public CompletableFuture<ByteBuf> getOrStartBuildFuture(
        UUID worldId,
        long chunkKey,
        Supplier<CompletableFuture<ByteBuf>> starter
    ) {
        return this.getOrStartBuildFuture(worldId, chunkKey, this.generationId.get(), starter);
    }

    public CompletableFuture<ByteBuf> getOrStartBuildFuture(
        UUID worldId,
        long chunkKey,
        long expectedGeneration,
        Supplier<CompletableFuture<ByteBuf>> starter
    ) {
        if (worldId == null || starter == null) {
            return CompletableFuture.completedFuture(null);
        }
        CacheGeneration current;
        BuildRegistration registration;
        synchronized (this.lifecycleLock) {
            current = this.generation;
            if (current == null || expectedGeneration != this.generationId.get()) {
                CompletableFuture<ByteBuf> cancelled = new CompletableFuture<>();
                cancelled.cancel(false);
                return cancelled;
            }
            registration = current.register(new ChunkKey(worldId, chunkKey));
        }

        ChunkKey key = new ChunkKey(worldId, chunkKey);
        if (!registration.startsBuild()) {
            return registration.future();
        }
        if (!registration.entry().isOpen()) {
            return registration.future();
        }

        CompletableFuture<ByteBuf> started;
        try {
            started = starter.get();
        } catch (Throwable throwable) {
            current.completeBuild(key, registration.entry(), null, throwable);
            return registration.future();
        }
        if (started == null) {
            current.completeBuild(key, registration.entry(), null, null);
            return registration.future();
        }
        if (!registration.entry().attachBackend(started)) {
            started.cancel(false);
        }
        try {
            started.whenComplete((payload, throwable) ->
                current.completeBuild(key, registration.entry(), payload, throwable));
        } catch (Throwable throwable) {
            current.completeBuild(key, registration.entry(), null, throwable);
        }
        return registration.future();
    }

    public void invalidate(UUID worldId, long chunkKey) {
        if (worldId == null) {
            return;
        }
        CacheGeneration current = this.generation;
        if (current != null) {
            current.invalidate(new ChunkKey(worldId, chunkKey));
        }
    }

    public void cancelBuild(UUID worldId, long chunkKey, long expectedGeneration) {
        if (worldId == null) {
            return;
        }
        synchronized (this.lifecycleLock) {
            if (expectedGeneration == this.generationId.get() && this.generation != null) {
                this.generation.cancelBuild(new ChunkKey(worldId, chunkKey));
            }
        }
    }

    public boolean shouldBypass(UUID worldId, long chunkKey) {
        if (worldId == null) {
            return true;
        }
        CacheGeneration current = this.generation;
        return current == null || current.shouldBypass(new ChunkKey(worldId, chunkKey));
    }

    public void markUnavailable(UUID worldId, long chunkKey) {
        this.markUnavailable(worldId, chunkKey, this.generationId.get());
    }

    public void markUnavailable(UUID worldId, long chunkKey, long expectedGeneration) {
        if (worldId == null) {
            return;
        }
        synchronized (this.lifecycleLock) {
            if (this.generation != null && expectedGeneration == this.generationId.get()) {
                this.generation.markUnavailable(
                    new ChunkKey(worldId, chunkKey),
                    Math.max(1L, this.configContainer.get().unavailableRetryMs())
                );
            }
        }
    }

    public boolean isTemporarilyUnavailable(UUID worldId, long chunkKey) {
        if (worldId == null) {
            return false;
        }
        CacheGeneration current = this.generation;
        return current != null && current.isTemporarilyUnavailable(new ChunkKey(worldId, chunkKey));
    }

    public void cleanUp() {
        CacheGeneration current = this.generation;
        if (current != null) {
            current.cleanUp();
        }
    }

    public long getEstimatedSize() {
        CacheGeneration current = this.generation;
        return current == null ? 0L : current.estimatedSize();
    }

    public void invalidateAll() {
        CacheGeneration current = this.generation;
        if (current != null) {
            current.invalidateAll();
        }
    }

    @OnDisable
    public void onDisable() {
        CacheGeneration previous;
        synchronized (this.lifecycleLock) {
            previous = this.generation;
            if (previous != null) {
                previous.retire();
            }
            this.generation = null;
            this.generationId.incrementAndGet();
        }
        if (previous != null) {
            previous.close();
        }
    }

    public record ChunkKey(UUID worldId, long chunkKey) {
    }

    private record BuildRegistration(
        BuildEntry entry,
        CompletableFuture<ByteBuf> future,
        boolean startsBuild
    ) {

        private static BuildRegistration stopped() {
            return new BuildRegistration(null, CompletableFuture.completedFuture(null), false);
        }
    }

    private static final class CacheGeneration implements AutoCloseable {

        private final Cache<ChunkKey, CachedPayload> serializedCache;
        private final Cache<ChunkKey, Boolean> bypassCache;
        private final Cache<ChunkKey, Long> unavailableUntilMs;
        private final Map<ChunkKey, BuildEntry> builds = new HashMap<>();
        private final int maxBuilds;
        private volatile boolean active = true;
        private boolean closed;

        private CacheGeneration(int ttlSeconds, int maxEntries, long bypassMs, long unavailableTtlMs) {
            this.maxBuilds = maxEntries;
            this.serializedCache = Caffeine.newBuilder()
                .maximumSize(maxEntries)
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                .expireAfterAccess(Duration.ofSeconds(Math.min(ttlSeconds, 60L)))
                .executor(Runnable::run)
                .removalListener((ChunkKey key, CachedPayload value, RemovalCause cause) -> close(value))
                .build();
            this.bypassCache = Caffeine.newBuilder()
                .maximumSize(maxEntries)
                .expireAfterWrite(Duration.ofMillis(bypassMs))
                .expireAfterAccess(Duration.ofMillis(Math.min(bypassMs, 60_000L)))
                .build();
            this.unavailableUntilMs = Caffeine.newBuilder()
                .maximumSize(maxEntries)
                .expireAfterWrite(Duration.ofMillis(unavailableTtlMs))
                .expireAfterAccess(Duration.ofMillis(Math.min(unavailableTtlMs, 60_000L)))
                .build();
        }

        private ByteBuf acquireSerialized(ChunkKey key) {
            if (!this.active) {
                return null;
            }
            CachedPayload payload = this.serializedCache.getIfPresent(key);
            return payload == null ? null : payload.acquire();
        }

        private synchronized BuildRegistration register(ChunkKey key) {
            if (!this.active) {
                return BuildRegistration.stopped();
            }
            BuildEntry existing = this.builds.get(key);
            if (existing != null) {
                CompletableFuture<ByteBuf> future = existing.subscribe();
                if (future != null) {
                    this.observeCancellation(key, existing, future);
                    return new BuildRegistration(existing, future, false);
                }
                if (existing.isCompleting()) {
                    ByteBuf cached = this.acquireSerialized(key);
                    return new BuildRegistration(
                        existing,
                        CompletableFuture.completedFuture(cached),
                        false
                    );
                }
                this.builds.remove(key, existing);
            }
            if (this.builds.size() >= this.maxBuilds) {
                return BuildRegistration.stopped();
            }

            BuildEntry entry = new BuildEntry();
            CompletableFuture<ByteBuf> future = entry.subscribe();
            this.builds.put(key, entry);
            this.observeCancellation(key, entry, future);
            return new BuildRegistration(entry, future, true);
        }

        private void observeCancellation(
            ChunkKey key,
            BuildEntry entry,
            CompletableFuture<ByteBuf> future
        ) {
            future.whenComplete((payload, throwable) -> {
                if (!future.isCancelled()) {
                    return;
                }
                boolean cancelBackend = false;
                synchronized (this) {
                    if (this.builds.get(key) == entry && entry.removeCancelledWaiter(future)) {
                        this.builds.remove(key, entry);
                        cancelBackend = true;
                    }
                }
                if (cancelBackend) {
                    entry.cancelBackend();
                }
            });
        }

        private void completeBuild(ChunkKey key, BuildEntry entry, ByteBuf payload, Throwable throwable) {
            CachedPayload source = CachedPayload.takeOwnership(payload);
            try {
                if (throwable == null && source != null && source.isReadable()) {
                    this.completeSuccessfulBuild(key, entry, source);
                } else {
                    this.rejectBuild(key, entry);
                }
            } finally {
                close(source);
            }
        }

        private void completeSuccessfulBuild(ChunkKey key, BuildEntry entry, CachedPayload source) {
            List<CompletableFuture<ByteBuf>> waiters;
            boolean accepted;
            synchronized (this) {
                accepted = this.active && this.builds.get(key) == entry && entry.isOpen();
                if (accepted) {
                    waiters = entry.beginCompletion();
                    this.storeSerialized(key, source);
                    try {
                        this.bypassCache.invalidate(key);
                    } catch (RuntimeException ignored) {
                        // Cache policy failures must not strand accepted build futures.
                    }
                } else {
                    waiters = this.rejectBuildLocked(key, entry);
                }
            }

            if (!accepted) {
                cancel(waiters);
                return;
            }
            for (CompletableFuture<ByteBuf> waiter : waiters) {
                if (!this.active || !entry.isCompleting()) {
                    cancel(waiter);
                    continue;
                }
                ByteBuf owned = source.acquire();
                if (owned == null) {
                    completeNull(waiter);
                } else {
                    completeOwned(waiter, owned);
                }
            }
            synchronized (this) {
                entry.finishCompletion();
                this.builds.remove(key, entry);
            }
        }

        private void rejectBuild(ChunkKey key, BuildEntry entry) {
            List<CompletableFuture<ByteBuf>> waiters;
            boolean cancelled;
            synchronized (this) {
                cancelled = !this.active || this.builds.get(key) != entry || !entry.isOpen();
                waiters = this.rejectBuildLocked(key, entry);
            }
            if (cancelled) {
                cancel(waiters);
            } else {
                completeNull(waiters);
            }
        }

        private List<CompletableFuture<ByteBuf>> rejectBuildLocked(ChunkKey key, BuildEntry entry) {
            this.builds.remove(key, entry);
            return entry.invalidate();
        }

        private void invalidate(ChunkKey key) {
            List<CompletableFuture<ByteBuf>> waiters = List.of();
            BuildEntry entry = null;
            try {
                synchronized (this) {
                    if (!this.active) {
                        return;
                    }
                    close(this.serializedCache.asMap().remove(key));
                    entry = this.builds.remove(key);
                    waiters = entry == null ? List.of() : entry.invalidate();
                    this.bypassCache.put(key, Boolean.TRUE);
                    this.unavailableUntilMs.invalidate(key);
                }
            } finally {
                if (entry != null) {
                    entry.cancelBackend();
                }
                cancel(waiters);
            }
        }

        private void cancelBuild(ChunkKey key) {
            List<CompletableFuture<ByteBuf>> waiters;
            BuildEntry entry;
            synchronized (this) {
                if (!this.active) {
                    return;
                }
                entry = this.builds.remove(key);
                waiters = entry == null ? List.of() : entry.invalidate();
            }
            if (entry != null) {
                entry.cancelBackend();
            }
            cancel(waiters);
        }

        private boolean shouldBypass(ChunkKey key) {
            return !this.active || Boolean.TRUE.equals(this.bypassCache.getIfPresent(key));
        }

        private synchronized void markUnavailable(ChunkKey key, long retryDelayMs) {
            if (!this.active) {
                return;
            }
            long now = System.currentTimeMillis();
            long retryAt = retryDelayMs >= Long.MAX_VALUE - now ? Long.MAX_VALUE : now + retryDelayMs;
            this.unavailableUntilMs.put(key, retryAt);
        }

        private boolean isTemporarilyUnavailable(ChunkKey key) {
            if (!this.active) {
                return false;
            }
            Long until = this.unavailableUntilMs.getIfPresent(key);
            return until != null && System.currentTimeMillis() < until;
        }

        private synchronized void cleanUp() {
            if (!this.active) {
                return;
            }
            this.serializedCache.cleanUp();
            this.bypassCache.cleanUp();
            this.unavailableUntilMs.cleanUp();
        }

        private synchronized long estimatedSize() {
            return this.active ? this.serializedCache.estimatedSize() : 0L;
        }

        private void invalidateAll() {
            RetiredBuilds retired = RetiredBuilds.empty();
            try {
                synchronized (this) {
                    if (!this.active) {
                        return;
                    }
                    retired = this.retireBuilds();
                    this.drainCaches();
                }
            } finally {
                retired.cancel();
            }
        }

        private void retire() {
            this.active = false;
        }

        @Override
        public void close() {
            RetiredBuilds retired = RetiredBuilds.empty();
            try {
                synchronized (this) {
                    if (this.closed) {
                        return;
                    }
                    this.closed = true;
                    this.active = false;
                    retired = this.retireBuilds();
                    this.drainCaches();
                }
            } finally {
                retired.cancel();
            }
        }

        private void storeSerialized(ChunkKey key, CachedPayload source) {
            ByteBuf owned = source.acquire();
            if (owned == null) {
                return;
            }
            CachedPayload cached = CachedPayload.takeOwnership(owned);
            try {
                CachedPayload previous = this.serializedCache.asMap().put(key, cached);
                close(previous);
            } catch (RuntimeException exception) {
                try {
                    this.serializedCache.asMap().remove(key, cached);
                } finally {
                    cached.close();
                }
            }
        }

        private RetiredBuilds retireBuilds() {
            List<CompletableFuture<ByteBuf>> waiters = new ArrayList<>();
            List<BuildEntry> entries = new ArrayList<>(this.builds.values());
            for (BuildEntry entry : entries) {
                waiters.addAll(entry.invalidate());
            }
            this.builds.clear();
            return new RetiredBuilds(waiters, entries);
        }

        private void drainCaches() {
            for (CachedPayload payload : this.serializedCache.asMap().values()) {
                close(payload);
            }
            this.serializedCache.invalidateAll();
            this.serializedCache.cleanUp();
            this.bypassCache.invalidateAll();
            this.bypassCache.cleanUp();
            this.unavailableUntilMs.invalidateAll();
            this.unavailableUntilMs.cleanUp();
        }

        private static void completeOwned(CompletableFuture<ByteBuf> future, ByteBuf payload) {
            boolean accepted = false;
            try {
                accepted = future.complete(payload);
            } finally {
                if (!accepted) {
                    CachedPayload.release(payload);
                }
            }
        }

        private static void completeNull(List<CompletableFuture<ByteBuf>> futures) {
            for (CompletableFuture<ByteBuf> future : futures) {
                completeNull(future);
            }
        }

        private static void completeNull(CompletableFuture<ByteBuf> future) {
            future.complete(null);
        }

        private static void cancel(List<CompletableFuture<ByteBuf>> futures) {
            for (CompletableFuture<ByteBuf> future : futures) {
                cancel(future);
            }
        }

        private static void cancel(CompletableFuture<ByteBuf> future) {
            future.cancel(false);
        }

        private static void close(CachedPayload payload) {
            if (payload != null) {
                payload.close();
            }
        }

        private record RetiredBuilds(
            List<CompletableFuture<ByteBuf>> waiters,
            List<BuildEntry> entries
        ) {

            private static RetiredBuilds empty() {
                return new RetiredBuilds(List.of(), List.of());
            }

            private void cancel() {
                for (BuildEntry entry : this.entries) {
                    entry.cancelBackend();
                }
                CacheGeneration.cancel(this.waiters);
            }
        }
    }

    private static final class BuildEntry {

        private final List<CompletableFuture<ByteBuf>> waiters = new ArrayList<>();
        private CompletableFuture<ByteBuf> backendFuture;
        private State state = State.OPEN;

        private synchronized CompletableFuture<ByteBuf> subscribe() {
            if (this.state != State.OPEN) {
                return null;
            }
            CompletableFuture<ByteBuf> future = new CompletableFuture<>();
            this.waiters.add(future);
            return future;
        }

        private synchronized boolean isOpen() {
            return this.state == State.OPEN;
        }

        private synchronized boolean isCompleting() {
            return this.state == State.COMPLETING;
        }

        private synchronized boolean attachBackend(CompletableFuture<ByteBuf> future) {
            if (this.state != State.OPEN) {
                return false;
            }
            this.backendFuture = future;
            return true;
        }

        private void cancelBackend() {
            CompletableFuture<ByteBuf> future;
            synchronized (this) {
                future = this.backendFuture;
                this.backendFuture = null;
            }
            if (future != null) {
                future.cancel(false);
            }
        }

        private synchronized List<CompletableFuture<ByteBuf>> beginCompletion() {
            if (this.state != State.OPEN) {
                return List.of();
            }
            this.state = State.COMPLETING;
            return new ArrayList<>(this.waiters);
        }

        private synchronized List<CompletableFuture<ByteBuf>> invalidate() {
            if (this.state == State.CLOSED) {
                return List.of();
            }
            this.state = State.CLOSED;
            List<CompletableFuture<ByteBuf>> result = new ArrayList<>(this.waiters);
            this.waiters.clear();
            return result;
        }

        private synchronized void finishCompletion() {
            this.state = State.CLOSED;
            this.backendFuture = null;
            this.waiters.clear();
        }

        private synchronized boolean removeCancelledWaiter(CompletableFuture<ByteBuf> future) {
            if (this.state != State.OPEN) {
                return false;
            }
            this.waiters.remove(future);
            if (!this.waiters.isEmpty()) {
                return false;
            }
            this.state = State.CLOSED;
            return true;
        }

        private enum State {
            OPEN,
            COMPLETING,
            CLOSED
        }
    }
}
