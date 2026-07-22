package me.mapacheee.extendedhorizons.fakechunks.dispatch;

import com.thewinterframework.service.annotation.Service;

import java.util.concurrent.atomic.AtomicInteger;

@Service
public final class GlobalGenerationLimiterService {

    private final AtomicInteger remaining = new AtomicInteger(0);
    private volatile int maxPerTick;

    public void reset(int maxPerTick) {
        this.maxPerTick = Math.max(0, maxPerTick);
        this.remaining.set(this.maxPerTick);
    }

    public boolean tryAcquire() {
        int current;
        do {
            current = this.remaining.get();
            if (current <= 0) {
                return false;
            }
        } while (!this.remaining.compareAndSet(current, current - 1));
        return true;
    }

    public void release() {
        int current;
        int next;
        do {
            current = this.remaining.get();
            next = current + 1;
            if (next > this.maxPerTick) {
                return;
            }
        } while (!this.remaining.compareAndSet(current, next));
    }
}


