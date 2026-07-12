package me.mapacheee.extendedhorizons.fakechunks.netty;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.util.AttributeKey;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runtime packet-id registry to avoid hardcoded protocol ids across versions.
 */
public final class PacketIdRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(PacketIdRegistry.class);
    private static final int UNRESOLVED = -1;
    private static final String[] PACKET_ID_METHODS = {"packetId", "id", "getId"};

    private static final AtomicInteger LEVEL_CHUNK_WITH_LIGHT_ID = new AtomicInteger(UNRESOLVED);
    private static final AtomicInteger CHUNK_CACHE_RADIUS_ID = new AtomicInteger(UNRESOLVED);
    private static final AttributeKey<Boolean> PENDING_LEVEL_CHUNK_PROBE = AttributeKey.valueOf("eh_pending_level_chunk_probe");
    private static final AttributeKey<Boolean> PENDING_RADIUS_PROBE = AttributeKey.valueOf("eh_pending_radius_probe");
    private static volatile boolean encoderResolveAttempted;

    private PacketIdRegistry() {}

    public static boolean hasLevelChunkWithLightId() {
        return LEVEL_CHUNK_WITH_LIGHT_ID.get() != UNRESOLVED;
    }

    public static int getLevelChunkWithLightId() {
        return LEVEL_CHUNK_WITH_LIGHT_ID.get();
    }

    public static void resolveLevelChunkWithLightId(int packetId) {
        if (packetId < 0) {
            return;
        }
        if (LEVEL_CHUNK_WITH_LIGHT_ID.compareAndSet(UNRESOLVED, packetId)) {
            LOGGER.info("EH resolved chunk packet id: {}", packetId);
        }
    }

    public static void markPendingLevelChunkProbe(Channel channel) {
        if (channel == null || hasLevelChunkWithLightId()) {
            return;
        }
        channel.attr(PENDING_LEVEL_CHUNK_PROBE).set(Boolean.TRUE);
    }

    public static boolean consumePendingLevelChunkProbe(Channel channel) {
        if (channel == null) {
            return false;
        }
        Boolean pending = channel.attr(PENDING_LEVEL_CHUNK_PROBE).get();
        if (Boolean.TRUE.equals(pending)) {
            channel.attr(PENDING_LEVEL_CHUNK_PROBE).set(Boolean.FALSE);
            return true;
        }
        return false;
    }
    public static boolean hasChunkCacheRadiusId() {
        return CHUNK_CACHE_RADIUS_ID.get() != UNRESOLVED;
    }

    public static int getChunkCacheRadiusId() {
        return CHUNK_CACHE_RADIUS_ID.get();
    }

    public static void resolveChunkCacheRadiusId(int packetId) {
        if (packetId < 0) {
            return;
        }
        if (CHUNK_CACHE_RADIUS_ID.compareAndSet(UNRESOLVED, packetId)) {
            LOGGER.info("EH resolved radius packet id: {}", packetId);
        }
    }

    public static void markPendingRadiusProbe(Channel channel) {
        if (channel == null || hasChunkCacheRadiusId()) {
            return;
        }
        channel.attr(PENDING_RADIUS_PROBE).set(Boolean.TRUE);
    }

    public static boolean consumePendingRadiusProbe(Channel channel) {
        if (channel == null) {
            return false;
        }
        Boolean pending = channel.attr(PENDING_RADIUS_PROBE).get();
        if (Boolean.TRUE.equals(pending)) {
            channel.attr(PENDING_RADIUS_PROBE).set(Boolean.FALSE);
            return true;
        }
        return false;
    }

    public static void resolveFromEncoder(Channel channel) {
        if ((hasLevelChunkWithLightId() && hasChunkCacheRadiusId()) || encoderResolveAttempted || channel == null) {
            return;
        }
        try {
            ChannelHandler encoder = channel.pipeline().get("encoder");
            if (encoder == null) {
                return;
            }
            Object protocolInfo = findProtocolInfo(encoder);
            if (protocolInfo == null) {
                if (resolveFromProtocolEnum()) {
                    encoderResolveAttempted = true;
                }
                return;
            }
            
            int chunkPacketId = lookupPacketId(protocolInfo, ClientboundLevelChunkWithLightPacket.class);
            if (chunkPacketId >= 0) {
                resolveLevelChunkWithLightId(chunkPacketId);
            }
            
            int radiusPacketId = lookupPacketId(protocolInfo, net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket.class);
            if (radiusPacketId >= 0) {
                resolveChunkCacheRadiusId(radiusPacketId);
            }
            
            if (hasLevelChunkWithLightId() && hasChunkCacheRadiusId()) {
                encoderResolveAttempted = true;
            }
        } catch (Throwable throwable) {
            LOGGER.error("EH encoder resolve: unexpected error", throwable);
        }
    }

    private static boolean resolveFromProtocolEnum() {
        try {
            Class<?> protocolClass = Class.forName("net.minecraft.network.protocol.Protocol");
            Method getPacketId = findGetPacketIdMethod(protocolClass);
            if (getPacketId == null) {
                LOGGER.info("EH resolveFromProtocolEnum: no suitable method found on Protocol");
                return false;
            }
            Object playProtocol = protocolClass.getEnumConstants()[0];

            int chunkId = (int) getPacketId.invoke(playProtocol, ClientboundLevelChunkWithLightPacket.class);
            if (chunkId >= 0) {
                resolveLevelChunkWithLightId(chunkId);
            }

            int radiusId = (int) getPacketId.invoke(playProtocol, net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket.class);
            if (radiusId >= 0) {
                resolveChunkCacheRadiusId(radiusId);
            }

            return hasLevelChunkWithLightId() && hasChunkCacheRadiusId();
        } catch (Throwable throwable) {
            LOGGER.info("EH resolveFromProtocolEnum failed: {}", throwable.getMessage());
            return false;
        }
    }

    private static Method findGetPacketIdMethod(Class<?> protocolClass) {
        for (Method method : protocolClass.getMethods()) {
            if (method.getParameterCount() == 1 && method.getParameterTypes()[0] == Class.class) {
                String name = method.getName();
                if (name.equals("getPacketId") || name.equals("getId") || name.startsWith("get")) {
                    Class<?> returnType = method.getReturnType();
                    if (returnType == int.class || returnType == Integer.class) {
                        return method;
                    }
                }
            }
        }
        for (Method method : protocolClass.getDeclaredMethods()) {
            if (method.getParameterCount() == 1 && method.getParameterTypes()[0] == Class.class) {
                String name = method.getName();
                if (name.equals("getPacketId") || name.equals("getId") || name.startsWith("get")) {
                    Class<?> returnType = method.getReturnType();
                    if (returnType == int.class || returnType == Integer.class) {
                        return method;
                    }
                }
            }
        }
        LOGGER.info("EH findGetPacketIdMethod: no suitable method found on {}", protocolClass.getName());
        return null;
    }

    private static Object findProtocolInfo(Object encoder) {
        for (Field field : encoder.getClass().getDeclaredFields()) {
            try {
                field.setAccessible(true);
                Object value = field.get(encoder);
                if (value == null) {
                    continue;
                }
                if (hasPacketIdLookup(value)) {
                    return value;
                }
                for (Field innerField : value.getClass().getDeclaredFields()) {
                    try {
                        innerField.setAccessible(true);
                        Object innerValue = innerField.get(value);
                        if (innerValue != null && hasPacketIdLookup(innerValue)) {
                            return innerValue;
                        }
                    } catch (Throwable throwable) {
                        LOGGER.debug("EH encoder resolve: failed to read inner field {}.{}", value.getClass().getSimpleName(), innerField.getName(), throwable);
                    }
                }
            } catch (Throwable throwable) {
                LOGGER.debug("EH encoder resolve: failed to read encoder field {}", field.getName(), throwable);
            }
        }
        return null;
    }

    private static boolean hasPacketIdLookup(Object obj) {
        Class<?> clazz = obj.getClass();
        for (Method method : clazz.getMethods()) {
            if (isPacketIdMethod(method)) {
                return true;
            }
        }
        for (Method method : clazz.getDeclaredMethods()) {
            if (isPacketIdMethod(method)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPacketIdMethod(Method method) {
        String name = method.getName();
        return (name.equals("packetId") || name.equals("id"))
            && method.getParameterCount() == 1
            && method.getReturnType() == int.class;
    }

    private static int lookupPacketId(Object protocolInfo, Class<?> packetClass) {
        for (String methodName : PACKET_ID_METHODS) {
            Method method = findMethod(protocolInfo.getClass(), methodName);
            if (method == null) {
                continue;
            }
            Class<?> paramType = method.getParameterTypes()[0];
            if (paramType != Class.class) {
                continue;
            }
            try {
                method.setAccessible(true);
                Object result = method.invoke(protocolInfo, packetClass);
                if (result instanceof Number num) {
                    return num.intValue();
                }
            } catch (Throwable throwable) {
                LOGGER.debug("EH encoder resolve: method {}.{}() failed", protocolInfo.getClass().getSimpleName(), methodName, throwable);
            }
        }
        return -1;
    }

    private static Method findMethod(Class<?> clazz, String name) {
        for (Method method : clazz.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 1
                && isValidReturnType(method.getReturnType())) {
                return method;
            }
        }
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 1
                && isValidReturnType(method.getReturnType())) {
                return method;
            }
        }
        return null;
    }

    private static boolean isValidReturnType(Class<?> returnType) {
        return returnType == int.class || Number.class.isAssignableFrom(returnType);
    }
}
