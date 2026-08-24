package me.mapacheee.extendedhorizons.fakechunks.backend;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

final class ChunkSectionCountWriter {

    private static final MethodHandle GET_NON_EMPTY_BLOCK_COUNT = createGetter("nonEmptyBlockCount");
    private static final MethodHandle GET_FLUID_COUNT = createGetter("fluidCount");

    private ChunkSectionCountWriter() {}

    static void write(FriendlyByteBuf out, LevelChunkSection section) {
        write(out, nonEmptyBlockCount(section), fluidCount(section));
    }

    static void write(FriendlyByteBuf out, short nonEmptyBlockCount, short fluidCount) {
        out.writeShort(nonEmptyBlockCount);
        out.writeShort(fluidCount);
    }

    static short nonEmptyBlockCount(LevelChunkSection section) {
        return read(GET_NON_EMPTY_BLOCK_COUNT, section, "non-empty block count");
    }

    static short fluidCount(LevelChunkSection section) {
        return read(GET_FLUID_COUNT, section, "fluid count");
    }

    private static MethodHandle createGetter(String fieldName) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(LevelChunkSection.class, MethodHandles.lookup());
            return lookup.findGetter(LevelChunkSection.class, fieldName, short.class)
                .asType(MethodType.methodType(short.class, LevelChunkSection.class));
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static short read(MethodHandle getter, LevelChunkSection section, String fieldDescription) {
        try {
            return (short) getter.invokeExact(section);
        } catch (Throwable throwable) {
            throw new IllegalStateException("Failed to read " + fieldDescription, throwable);
        }
    }
}
