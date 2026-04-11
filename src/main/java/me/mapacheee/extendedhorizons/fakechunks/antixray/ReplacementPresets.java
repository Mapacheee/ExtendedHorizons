package me.mapacheee.extendedhorizons.fakechunks.antixray;

import java.util.Arrays;

@FunctionalInterface
public interface ReplacementPresets {

    static ReplacementPresets createStatic(int... presets) {
        Arrays.sort(presets);
        return __ -> presets;
    }

    static ReplacementPresets createStaticZeroSplit(int[] abovePresets, int[] belowPresets) {
        Arrays.sort(abovePresets);
        Arrays.sort(belowPresets);
        return sectionY -> sectionY < 0 ? belowPresets : abovePresets;
    }

    int[] getPresets(int sectionY);
}

