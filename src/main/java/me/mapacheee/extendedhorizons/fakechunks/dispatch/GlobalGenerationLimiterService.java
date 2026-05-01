package me.mapacheee.extendedhorizons.fakechunks.dispatch;

import com.thewinterframework.service.annotation.Service;

import java.util.concurrent.atomic.AtomicInteger;

@Service
public final class GlobalGenerationLimiterService {

    private final AtomicInteger remaining = new AtomicInteger(0);

    public void reset(int maxPerTick) {
        this.remaining.set(Math.max(0, maxPerTick));
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
}

