package me.mapacheee.extendedhorizons.view;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkPacerTest {

  @Test
  void acknowledgesOnlyTheBytesThatPrecededTheProbe() {
    long now = 5_000_000_000L;
    int chunk = 64 * 1024;
    var pacer = new ChunkPacer();
    pacer.sent(chunk, now);
    int firstProbe = pacer.startProbe(now);
    pacer.sent(chunk, now);
    assertFalse(pacer.allows(chunk, now));
    assertFalse(pacer.acknowledge(firstProbe ^ 1, now + 20_000_000L));
    assertTrue(pacer.acknowledge(firstProbe, now + 20_000_000L));
    assertTrue(pacer.allows(chunk, now + 20_000_000L));
    assertFalse(pacer.allows(chunk * 2, now + 20_000_000L));

    long later = now + 100_000_000L;
    int secondProbe = pacer.startProbe(later);
    assertTrue(pacer.acknowledge(secondProbe, later + 150_000_000L));
    pacer.sent(chunk, later + 150_000_000L);
    assertFalse(pacer.allows(chunk, later + 150_000_000L));
  }

  @Test
  void continuesAtAReducedPaceWhenPongsAreUnavailable() {
    long now = 5_000_000_000L;
    int chunk = 64 * 1024;
    var pacer = new ChunkPacer();
    pacer.sent(chunk, now);
    pacer.startProbe(now);

    long timeout = now + 2_000_000_000L;
    pacer.tick(timeout);
    assertTrue(pacer.allows(chunk, timeout));
    pacer.sent(chunk, timeout);
    assertFalse(pacer.allows(chunk, timeout + 50_000_000L));
    assertTrue(pacer.allows(chunk, timeout + 200_000_000L));

    long retry = timeout + 5_000_000_000L;
    pacer.sent(chunk, retry);
    assertTrue(pacer.shouldProbe(retry));
    int probe = pacer.startProbe(retry);
    assertTrue(pacer.allows(chunk, retry + 200_000_000L));
    assertTrue(pacer.acknowledge(probe, retry + 220_000_000L));
    assertTrue(pacer.allows(chunk, retry + 220_000_000L));
  }
}
