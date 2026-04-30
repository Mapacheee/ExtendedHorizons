package me.mapacheee.extendedhorizons;

import com.google.inject.Binder;
import com.google.inject.Scopes;
import com.thewinterframework.paper.PaperWinterPlugin;
import com.thewinterframework.plugin.WinterBootPlugin;
import com.thewinterframework.service.annotation.Service;
import me.mapacheee.extendedhorizons.fakechunks.backend.ChunkBackend;
import me.mapacheee.extendedhorizons.fakechunks.backend.PaperChunkBackend;
import me.mapacheee.extendedhorizons.fakechunks.farplayers.backend.FarPlayerBackend;
import me.mapacheee.extendedhorizons.fakechunks.farplayers.backend.PaperFarPlayerBackend;

@WinterBootPlugin
public final class ExtendedHorizonsPlugin extends PaperWinterPlugin {

    private static volatile ExtendedHorizonsPlugin instance;
    private static volatile boolean loading = false;

    public static ExtendedHorizonsPlugin getInstance() {
        return instance;
    }

    public static <T> T getService(Class<T> type) {
        ExtendedHorizonsPlugin current = instance;
        if (current == null || loading) {
            throw new IllegalStateException("ExtendedHorizons plugin is not loaded yet");
        }
        return current.getInjector().getInstance(type);
    }

    @Override
    public void onPluginLoad() {
        loading = true;
        try {
            super.onPluginLoad();
            instance = this;
        } finally {
            loading = false;
        }
    }

    @Override
    public void onPluginDisable() {
        instance = null;
        super.onPluginDisable();
    }

    @Override
    public void configure(Binder binder) {
        binder.bindScope(Service.class, Scopes.SINGLETON);
        binder.bind(ChunkBackend.class).to(PaperChunkBackend.class).in(Scopes.SINGLETON);
        binder.bind(FarPlayerBackend.class).to(PaperFarPlayerBackend.class).in(Scopes.SINGLETON);
    }
}

