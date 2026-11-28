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
  public final LongAdder paced = new LongAdder();
  public final LongAdder measuredTerrainBytes = new LongAdder();
  public final LongAdder estimatedTerrainBytes = new LongAdder();
  public final LongAdder suppressed = new LongAdder();
  public final LongAdder refreshes = new LongAdder();
  public final LongAdder updated = new LongAdder();
  public final LongAdder updateBytes = new LongAdder();
  public final LongAdder bedrockDeltas = new LongAdder();
  public final LongAdder bedrockFullUpdates = new LongAdder();

  public Map<String, Long> snapshot() {
    return Map.ofEntries(Map.entry("sent", this.sent.sum()), Map.entry("bytes", this.bytes.sum()),
      Map.entry("obsolete", this.obsolete.sum()), Map.entry("unavailable", this.unavailable.sum()),
      Map.entry("failures", this.failures.sum()), Map.entry("throttled", this.throttled.sum()),
      Map.entry("paced", this.paced.sum()), Map.entry("suppressedChunks", this.suppressed.sum()),
      Map.entry("measuredTerrainBytes", this.measuredTerrainBytes.sum()),
      Map.entry("estimatedTerrainBytes", this.estimatedTerrainBytes.sum()),
      Map.entry("refreshes", this.refreshes.sum()), Map.entry("updated", this.updated.sum()),
      Map.entry("updateBytes", this.updateBytes.sum()), Map.entry("bedrockDeltas", this.bedrockDeltas.sum()),
      Map.entry("bedrockFullUpdates", this.bedrockFullUpdates.sum()));
  }
}
