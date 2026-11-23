package me.mapacheee.extendedhorizons.view;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Bounds the extended terrain queued ahead of gameplay packets. A pong confirms receipt
 * of the stream prefix preceding its ping, including bytes already accepted by the socket.
 */
final class ChunkPacer {

  private static final int MIN_WINDOW = 32 * 1024;
  private static final int MAX_WINDOW = 2 * 1024 * 1024;
  private static final long PROBE_INTERVAL = 100_000_000L;
  private static final long PROBE_TIMEOUT = 2_000_000_000L;
  private static final long FALLBACK_RETRY = 5_000_000_000L;
  private static final long FALLBACK_BYTES_PER_SECOND = 384 * 1024;

  private int window = 128 * 1024;
  private int outstanding;
  private int probeBytes;
  private int probeId;
  private long probeSentAt;
  private long minimumRtt = Long.MAX_VALUE;
  private long nextProbe;
  private boolean fallback;
  private long nextFallbackSend;

  void tick(long now) {
    if (this.probeBytes != 0 && now - this.probeSentAt >= PROBE_TIMEOUT) {
      this.outstanding = 0;
      this.probeBytes = 0;
      this.window = MIN_WINDOW;
      this.nextProbe = now + FALLBACK_RETRY;
      if (!this.fallback) {
        this.fallback = true;
        this.nextFallbackSend = now;
      }
    }
  }

  boolean allows(int bytes, long now) {
    if (this.fallback) {
      return now - this.nextFallbackSend >= 0;
    }
    return this.outstanding == 0 || bytes <= this.window - this.outstanding;
  }

  void sent(int bytes, long now) {
    if (this.fallback) {
      this.nextFallbackSend = now + bytes * 1_000_000_000L / FALLBACK_BYTES_PER_SECOND;
    }
    this.outstanding += bytes;
  }

  boolean shouldProbe(long now) {
    return this.outstanding > 0 && this.probeBytes == 0 && (this.nextProbe == 0 || now - this.nextProbe >= 0);
  }

  int startProbe(long now) {
    this.probeBytes = this.outstanding;
    this.probeSentAt = now;
    this.nextProbe = now + PROBE_INTERVAL;
    this.probeId = ThreadLocalRandom.current().nextInt();
    return this.probeId;
  }

  boolean acknowledge(int id, long now) {
    if (this.probeBytes == 0 || this.probeId != id) {
      return false;
    }
    long rtt = now - this.probeSentAt;
    if (this.fallback) {
      this.fallback = false;
      this.minimumRtt = rtt;
    } else {
      this.minimumRtt = Math.min(this.minimumRtt, rtt);
      long queueDelay = rtt - this.minimumRtt;
      if (queueDelay > 70_000_000L) {
        this.window = Math.max(MIN_WINDOW, this.window * 2 / 3);
      } else if (queueDelay <= 30_000_000L && this.outstanding >= this.window / 2) {
        this.window = Math.min(MAX_WINDOW, this.window + Math.max(16 * 1024, this.window / 4));
      }
    }
    this.outstanding -= this.probeBytes;
    this.probeBytes = 0;
    return true;
  }
}
