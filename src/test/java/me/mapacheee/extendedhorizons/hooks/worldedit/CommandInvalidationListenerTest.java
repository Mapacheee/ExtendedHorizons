package me.mapacheee.extendedhorizons.hooks.worldedit;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommandInvalidationListenerTest {
    @Test void rejectsOversizedAndOverflowingAreasBeforeEnumeration() {
        assertTrue(CommandInvalidationListener.chunkCount(-30_000_000, 30_000_000,
            -30_000_000, 30_000_000) > CommandInvalidationListener.MAX_COMMAND_CHUNKS);
        assertEquals(-1, CommandInvalidationListener.chunkCount(29_999_999, 3_000_000_000L, 0, 0));
        assertEquals(4, CommandInvalidationListener.chunkCount(-1, 0, -1, 0));
    }

    @Test void rejectsNonFiniteAndUnsupportedCoordinates() {
        for (String value : new String[]{"NaN", "Infinity", "1e100", "^1", "~Infinity"}) {
            assertThrows(NumberFormatException.class, () -> CommandInvalidationListener.parseCoordinate(value, 0));
        }
        assertEquals(-2, CommandInvalidationListener.parseCoordinate("~-0.5", -1));
    }
}
