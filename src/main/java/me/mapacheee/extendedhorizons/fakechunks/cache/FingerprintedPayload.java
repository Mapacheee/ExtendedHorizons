package me.mapacheee.extendedhorizons.fakechunks.cache;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.DuplicatedByteBuf;

final class FingerprintedPayload extends DuplicatedByteBuf {

  private final Fingerprint fingerprint;

  private FingerprintedPayload(ByteBuf source, Fingerprint fingerprint) {
    super(source.asReadOnly());
    this.fingerprint = fingerprint;
  }

  static ByteBuf takeOwnership(ByteBuf source) {
    return source == null ? null
      : new FingerprintedPayload(source, new Fingerprint(source.readerIndex(), source.writerIndex()));
  }

  byte[] fingerprint() {
    return this.readerIndex() == this.fingerprint.readerIndex && this.writerIndex() == this.fingerprint.writerIndex
      ? this.fingerprint.get(this) : null;
  }

  @Override
  public ByteBuf duplicate() {
    return new FingerprintedPayload(this, this.fingerprint);
  }

  @Override
  public ByteBuf retainedDuplicate() {
    return this.duplicate().retain();
  }

  private static final class Fingerprint {
    private final int readerIndex;
    private final int writerIndex;
    private volatile byte[] value;

    private Fingerprint(int readerIndex, int writerIndex) {
      this.readerIndex = readerIndex;
      this.writerIndex = writerIndex;
    }

    private byte[] get(ByteBuf source) {
      byte[] current = this.value;
      if (current != null) return current;
      synchronized (this) {
        if (this.value == null) this.value = CompressedPayloadCacheService.computeFingerprint(source);
        return this.value;
      }
    }
  }
}
