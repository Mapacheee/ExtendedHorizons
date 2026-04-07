package me.mapacheee.extendedhorizons;

import com.google.inject.Binder;
import com.google.inject.Scopes;
import com.thewinterframework.paper.PaperWinterPlugin;
import com.thewinterframework.plugin.WinterBootPlugin;
import com.thewinterframework.service.annotation.Service;
import me.mapacheee.extendedhorizons.fakechunks.backend.ChunkBackend;
import me.mapacheee.extendedhorizons.fakechunks.backend.PaperChunkBackend;

@WinterBootPlugin
public final class ExtendedHorizonsPlugin extends PaperWinterPlugin {

    private static ExtendedHorizonsPlugin instance;

    public static ExtendedHorizonsPlugin getInstance() {
        return instance;
    }

    public static <T> T getService(Class<T> type) {
        if (instance == null) {
            throw new IllegalStateException("ExtendedHorizons plugin is not loaded yet");
        }
        return instance.getInjector().getInstance(type);
    }

    @Override
    public void onPluginLoad() {
        super.onPluginLoad();
        instance = this;
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
    }
}

