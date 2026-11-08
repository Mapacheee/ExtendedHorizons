package me.mapacheee.extendedhorizons.view;

import java.util.HashSet;
import java.util.Set;

public final class SendBudget {

  private final long rate;
  private final long burst;
  private double available;
  private long updated = System.nanoTime();
  private final Set<Share> participants = new HashSet<>();
  private long nextCount;
  private int active = 1;

  public SendBudget(long rate) {
    this.rate = rate;
    this.burst = Math.max(65536, rate / 10);
    this.available = this.burst;
  }

  public synchronized boolean take(int bytes, long now) {
    this.refill(now);
    if (this.available < Math.min(bytes, this.burst)) {
      return false;
    }
    this.available -= bytes;
    return true;
  }

  public synchronized void refund(int bytes) {
    this.available = Math.min(this.burst, this.available + bytes);
  }

  public Share share() {
    return new Share();
  }

  public synchronized void remove(Share share) {
    this.participants.remove(share);
    this.nextCount = 0;
  }

  public synchronized boolean take(int bytes, long now, Share share) {
    if (this.participants.add(share)) {
      this.nextCount = 0;
    }
    share.demanded = Math.max(share.demanded, now);
    if (now >= this.nextCount) {
      this.active = 0;
      for (Share participant : this.participants) {
        if (now - participant.demanded < 1_000_000_000L) {
          this.active++;
        }
      }
      this.active = Math.max(1, this.active);
      this.nextCount = now + 100_000_000L;
    }
    long shareRate = this.rate / this.active;
    long shareBurst = Math.max(65536, shareRate / 10);
    if (now > share.updated) {
      share.available = Math.min(shareBurst, share.available + (now - share.updated) * (shareRate / 1.0e9));
      share.updated = now;
    }
    this.refill(now);
    if (share.available < Math.min(bytes, shareBurst) || this.available < Math.min(bytes, this.burst)) {
      return false;
    }
    share.available -= bytes;
    this.available -= bytes;
    return true;
  }

  public static final class Share {
    private long updated = System.nanoTime();
    private long demanded;
    private double available;
  }

  private void refill(long now) {
    if (now <= this.updated) {
      return;
    }
    this.available = Math.min(this.burst, this.available + (now - this.updated) * (this.rate / 1.0e9));
    this.updated = now;
  }
}
