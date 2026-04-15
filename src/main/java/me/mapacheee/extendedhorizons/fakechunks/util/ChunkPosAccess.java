package me.mapacheee.extendedhorizons.fakechunks.util;

import net.minecraft.world.level.ChunkPos;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;

public final class ChunkPosAccess {

    private static final Accessor ACCESSOR = resolveAccessor();

    private ChunkPosAccess() {}

    public static int x(ChunkPos pos) {
        return ACCESSOR.x(pos);
    }

    public static int z(ChunkPos pos) {
        return ACCESSOR.z(pos);
    }

    private static Accessor resolveAccessor() {
        MethodHandles.Lookup publicLookup = MethodHandles.publicLookup();

        MethodHandle xHandle = findNoArgIntMethod(publicLookup, "x");
        MethodHandle zHandle = findNoArgIntMethod(publicLookup, "z");
        if (xHandle != null && zHandle != null) {
            return new MethodHandleAccessor(xHandle, zHandle);
        }

        xHandle = findNoArgIntMethod(publicLookup, "getX");
        zHandle = findNoArgIntMethod(publicLookup, "getZ");
        if (xHandle != null && zHandle != null) {
            return new MethodHandleAccessor(xHandle, zHandle);
        }

        Field xField = findField("x");
        Field zField = findField("z");
        if (xField != null && zField != null) {
            return new ReflectionFieldAccessor(xField, zField);
        }

        throw new IllegalStateException("Unable to resolve ChunkPos coordinate accessor");
    }

    private static MethodHandle findNoArgIntMethod(MethodHandles.Lookup lookup, String methodName) {
        try {
            return lookup.findVirtual(ChunkPos.class, methodName, MethodType.methodType(int.class));
        } catch (NoSuchMethodException | IllegalAccessException ignored) {
            return null;
        }
    }

    private static Field findField(String fieldName) {
        try {
            Field field = ChunkPos.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException ignored) {
            return null;
        }
    }

    private sealed interface Accessor permits MethodHandleAccessor, ReflectionFieldAccessor {
        int x(ChunkPos pos);

        int z(ChunkPos pos);
    }

    private static final class MethodHandleAccessor implements Accessor {

        private final MethodHandle xHandle;
        private final MethodHandle zHandle;

        private MethodHandleAccessor(MethodHandle xHandle, MethodHandle zHandle) {
            this.xHandle = xHandle;
            this.zHandle = zHandle;
        }

        @Override
        public int x(ChunkPos pos) {
            try {
                return (int) this.xHandle.invoke(pos);
            } catch (Throwable throwable) {
                throw new IllegalStateException("Unable to read ChunkPos.x", throwable);
            }
        }

        @Override
        public int z(ChunkPos pos) {
            try {
                return (int) this.zHandle.invoke(pos);
            } catch (Throwable throwable) {
                throw new IllegalStateException("Unable to read ChunkPos.z", throwable);
            }
        }
    }

    private static final class ReflectionFieldAccessor implements Accessor {

        private final Field xField;
        private final Field zField;

        private ReflectionFieldAccessor(Field xField, Field zField) {
            this.xField = xField;
            this.zField = zField;
        }

        @Override
        public int x(ChunkPos pos) {
            try {
                return this.xField.getInt(pos);
            } catch (IllegalAccessException throwable) {
                throw new IllegalStateException("Unable to read ChunkPos.x", throwable);
            }
        }

        @Override
        public int z(ChunkPos pos) {
            try {
                return this.zField.getInt(pos);
            } catch (IllegalAccessException throwable) {
                throw new IllegalStateException("Unable to read ChunkPos.z", throwable);
            }
        }
    }
}

