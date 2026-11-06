package me.mapacheee.extendedhorizons.view;

import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

public final class ViewMetrics {

  public final LongAdder sent = new LongAdder();
  public final LongAdder bytes = new LongAdder();
  public final LongAdder obsolete = new LongAdder();
  public final LongAdder unavailable = new LongAdder();
  public final LongAdder failures = new LongAdder();
  public final LongAdder throttled = new LongAdder();
  public final LongAdder suppressed = new LongAdder();

  public Map<String, Long> snapshot() {
    return Map.of("sent", this.sent.sum(), "bytes", this.bytes.sum(), "obsolete", this.obsolete.sum(),
      "unavailable", this.unavailable.sum(), "failures", this.failures.sum(), "throttled", this.throttled.sum(),
      "suppressedChunks", this.suppressed.sum());
  }
}
