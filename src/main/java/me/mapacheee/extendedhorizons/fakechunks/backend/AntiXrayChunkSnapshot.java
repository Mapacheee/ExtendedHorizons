package me.mapacheee.extendedhorizons.fakechunks.backend;

import io.netty.buffer.ByteBuf;
import me.mapacheee.extendedhorizons.fakechunks.antixray.AntiXrayProcessor;

record AntiXrayChunkSnapshot(
    AntiXrayProcessor antiXrayProcessor,
    ByteBuf heightmaps,
    ByteBuf light,
    AntiXraySectionSnapshot[] sections
) {

    void release() {
        this.heightmaps.release();
        this.light.release();
        for (AntiXraySectionSnapshot section : this.sections) {
            if (section != null) {
                section.release();
            }
        }
    }
}

