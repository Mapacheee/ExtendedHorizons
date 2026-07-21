package me.mapacheee.extendedhorizons.runtime;

import com.thewinterframework.service.annotation.Service;

import java.util.concurrent.atomic.LongAdder;

@Service
public final class ChunkBuildMetricsService {

    private static final long NANOS_TO_MICROS = 1_000L;

    private final LongAdder antiXraySnapshotCount = new LongAdder();
    private final LongAdder antiXraySnapshotNanos = new LongAdder();

    private final LongAdder antiXrayAsyncCount = new LongAdder();
    private final LongAdder antiXrayAsyncNanos = new LongAdder();

    private final LongAdder antiXrayFinalCacheHit = new LongAdder();
    private final LongAdder antiXrayFinalCacheMiss = new LongAdder();

    private final LongAdder antiXrayFallbackCount = new LongAdder();

    public void recordAntiXraySnapshot(long nanos) {
        this.antiXraySnapshotCount.increment();
        this.antiXraySnapshotNanos.add(Math.max(0L, nanos));
    }

    public void recordAntiXrayAsync(long nanos) {
        this.antiXrayAsyncCount.increment();
        this.antiXrayAsyncNanos.add(Math.max(0L, nanos));
    }

    public void recordAntiXrayFinalCacheHit() {
        this.antiXrayFinalCacheHit.increment();
    }

    public void recordAntiXrayFinalCacheMiss() {
        this.antiXrayFinalCacheMiss.increment();
    }

    public void recordAntiXrayFallback() {
        this.antiXrayFallbackCount.increment();
    }

    public Snapshot snapshotAndReset() {
        long snapshotCount = this.antiXraySnapshotCount.sumThenReset();
        long snapshotNanos = this.antiXraySnapshotNanos.sumThenReset();

        long asyncCount = this.antiXrayAsyncCount.sumThenReset();
        long asyncNanos = this.antiXrayAsyncNanos.sumThenReset();

        long cacheHit = this.antiXrayFinalCacheHit.sumThenReset();
        long cacheMiss = this.antiXrayFinalCacheMiss.sumThenReset();

        long fallback = this.antiXrayFallbackCount.sumThenReset();

        return new Snapshot(snapshotCount, snapshotNanos, asyncCount, asyncNanos, cacheHit, cacheMiss, fallback);
    }

    public record Snapshot(
        long antiXraySnapshotCount,
        long antiXraySnapshotNanos,
        long antiXrayAsyncCount,
        long antiXrayAsyncNanos,
        long antiXrayFinalCacheHit,
        long antiXrayFinalCacheMiss,
        long antiXrayFallbackCount
    ) {
        public long antiXraySnapshotAvgMicros() {
            if (this.antiXraySnapshotCount <= 0) {
                return 0L;
            }
            return (this.antiXraySnapshotNanos / this.antiXraySnapshotCount) / NANOS_TO_MICROS;
        }

        public long antiXrayAsyncAvgMicros() {
            if (this.antiXrayAsyncCount <= 0) {
                return 0L;
            }
            return (this.antiXrayAsyncNanos / this.antiXrayAsyncCount) / NANOS_TO_MICROS;
        }

        public double antiXrayCacheHitRate() {
            long total = this.antiXrayFinalCacheHit + this.antiXrayFinalCacheMiss;
            if (total <= 0L) {
                return 0.0d;
            }
            return (double) this.antiXrayFinalCacheHit / (double) total;
        }

        public boolean hasData() {
            return this.antiXraySnapshotCount > 0
                || this.antiXrayAsyncCount > 0
                || this.antiXrayFinalCacheHit > 0
                || this.antiXrayFinalCacheMiss > 0
                || this.antiXrayFallbackCount > 0;
        }
    }
}

