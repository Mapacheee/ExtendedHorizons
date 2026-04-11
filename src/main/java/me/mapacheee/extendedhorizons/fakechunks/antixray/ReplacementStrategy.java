package me.mapacheee.extendedhorizons.fakechunks.antixray;

@FunctionalInterface
public interface ReplacementStrategy {

    ReplacementStrategy STATIC_ZERO = () -> 0;

    int get();
}


