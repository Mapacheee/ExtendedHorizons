package me.mapacheee.extendedhorizons.fakechunks.backend;

import io.netty.buffer.ByteBuf;

record AntiXraySectionSnapshot(
    int sectionY,
    short nonEmptyBlockCount,
    short fluidCount,
    ByteBuf states,
    ByteBuf biomes
) {

    void release() {
        this.states.release();
        this.biomes.release();
    }
}


