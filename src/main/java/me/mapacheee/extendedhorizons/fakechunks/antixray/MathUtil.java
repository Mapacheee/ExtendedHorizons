package me.mapacheee.extendedhorizons.fakechunks.antixray;

public final class MathUtil {

    private MathUtil() {
    }

    public static int log2(int i) {
        return 31 - Integer.numberOfLeadingZeros(i);
    }

    public static int ceilLog2(int i) {
        boolean pow2 = i != 0 && (i & -i) == i;
        return log2(i) + (pow2 ? 0 : 1);
    }
}

