package me.mapacheee.extendedhorizons.util;

import net.minecraft.world.entity.EntityType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class NmsCompat {

    private static final Logger LOGGER = LoggerFactory.getLogger(NmsCompat.class);

    public static final EntityType<?> PLAYER_ENTITY_TYPE;

    static {
        EntityType<?> playerType = null;
        try {
            Class<?> entityTypesClass = Class.forName("net.minecraft.world.entity.EntityTypes");
            playerType = (EntityType<?>) entityTypesClass.getField("PLAYER").get(null);
            LOGGER.info("NMS Compat: resolved PLAYER_ENTITY_TYPE from EntityTypes.PLAYER");
        } catch (Throwable t1) {
            try {
                playerType = (EntityType<?>) EntityType.class.getField("PLAYER").get(null);
                LOGGER.info("NMS Compat: resolved PLAYER_ENTITY_TYPE from EntityType.PLAYER");
            } catch (Throwable t2) {
                LOGGER.error("NMS Compat: failed to resolve PLAYER_ENTITY_TYPE!", t2);
            }
        }
        PLAYER_ENTITY_TYPE = playerType;
    }

    private NmsCompat() {}
}
