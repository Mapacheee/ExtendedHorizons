package me.mapacheee.extendedhorizons.view;

public final class SendBudget {

  private final long rate;
  private final long burst;
  private double available;
  private long updated = System.nanoTime();

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

  private void refill(long now) {
    if (now <= this.updated) {
      return;
    }
    this.available = Math.min(this.burst, this.available + (now - this.updated) * (this.rate / 1.0e9));
    this.updated = now;
  }
}
