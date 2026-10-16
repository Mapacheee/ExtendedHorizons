package me.mapacheee.extendedhorizons.fakechunks.disk;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

final class NbtByteArrayInput implements DataInput {

  private static final VarHandle INTS = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.BIG_ENDIAN);
  private static final VarHandle LONGS = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);
  private static final long HIGH_BITS = 0x8080808080808080L;

  private final byte[] data;
  private int position;

  NbtByteArrayInput(byte[] data) {
    this.data = Objects.requireNonNull(data);
  }

  private void require(int count) throws EOFException {
    if (count > this.data.length - this.position) {
      throw new EOFException();
    }
  }

  @Override
  public void readFully(byte[] target) throws IOException {
    this.readFully(target, 0, target.length);
  }

  @Override
  public void readFully(byte[] target, int offset, int length) throws IOException {
    Objects.checkFromIndexSize(offset, length, target.length);
    this.require(length);
    System.arraycopy(this.data, this.position, target, offset, length);
    this.position += length;
  }

  @Override
  public int skipBytes(int count) {
    int skipped = Math.min(Math.max(0, count), this.data.length - this.position);
    this.position += skipped;
    return skipped;
  }

  @Override
  public boolean readBoolean() throws IOException {
    return this.readUnsignedByte() != 0;
  }

  @Override
  public byte readByte() throws IOException {
    this.require(1);
    return this.data[this.position++];
  }

  @Override
  public int readUnsignedByte() throws IOException {
    return this.readByte() & 0xFF;
  }

  @Override
  public short readShort() throws IOException {
    return (short) this.readUnsignedShort();
  }

  @Override
  public int readUnsignedShort() throws IOException {
    this.require(2);
    int result = (this.data[this.position] & 0xFF) << 8 | this.data[this.position + 1] & 0xFF;
    this.position += 2;
    return result;
  }

  @Override
  public char readChar() throws IOException {
    return (char) this.readUnsignedShort();
  }

  @Override
  public int readInt() throws IOException {
    this.require(Integer.BYTES);
    int result = (int) INTS.get(this.data, this.position);
    this.position += Integer.BYTES;
    return result;
  }

  @Override
  public long readLong() throws IOException {
    this.require(Long.BYTES);
    long result = (long) LONGS.get(this.data, this.position);
    this.position += Long.BYTES;
    return result;
  }

  @Override
  public float readFloat() throws IOException {
    return Float.intBitsToFloat(this.readInt());
  }

  @Override
  public double readDouble() throws IOException {
    return Double.longBitsToDouble(this.readLong());
  }

  @Override
  public String readLine() {
    if (this.position == this.data.length) {
      return null;
    }
    int start = this.position;
    while (this.position < this.data.length) {
      byte value = this.data[this.position++];
      if (value == '\n' || value == '\r') {
        int end = this.position - 1;
        if (value == '\r' && this.position < this.data.length && this.data[this.position] == '\n') {
          this.position++;
        }
        return new String(this.data, start, end - start, StandardCharsets.ISO_8859_1);
      }
    }
    return new String(this.data, start, this.position - start, StandardCharsets.ISO_8859_1);
  }

  @Override
  public String readUTF() throws IOException {
    int start = this.position;
    int length = this.readUnsignedShort();
    this.require(length);
    int end = this.position + length;
    int cursor = this.position;
    while (cursor <= end - Long.BYTES) {
      if (((long) LONGS.get(this.data, cursor) & HIGH_BITS) != 0L) {
        this.position = start;
        return DataInputStream.readUTF(this);
      }
      cursor += Long.BYTES;
    }
    while (cursor < end) {
      if (this.data[cursor++] < 0) {
        this.position = start;
        return DataInputStream.readUTF(this);
      }
    }
    String result = length == 0 ? "" : new String(this.data, this.position, length, StandardCharsets.ISO_8859_1);
    this.position = end;
    return result;
  }
}
