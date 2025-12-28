package me.mapacheee.extendedhorizons.api;

import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.thewinterframework.module.annotation.ModuleComponent;
import com.thewinterframework.plugin.WinterPlugin;
import com.thewinterframework.plugin.module.PluginModule;

/**
 * Winter module that binds the ExtendedHorizons API interface to its
 * implementation.
 * This allows external plugins to access the API using:
 * 
 * ExtendedHorizonsAPI api =
 * ExtendedHorizonsPlugin.getService(ExtendedHorizonsAPI.class);
 */
@ModuleComponent
public class APIModule implements PluginModule {

    @Override
    public boolean onLoad(WinterPlugin plugin) {
        return true;
    }

    @Override
    public boolean onEnable(WinterPlugin plugin) {
        return true;
    }

    @Override
    public boolean onDisable(WinterPlugin plugin) {
        return true;
    }

    /**
     * Provides the ExtendedHorizons API implementation.
     * This binding allows external plugins to request the API interface
     * and receive the concrete implementation.
     *
     * @param impl The API implementation
     * @return The API interface
     */
    @Provides
    @Singleton
    public ExtendedHorizonsAPI provideAPI(ExtendedHorizonsAPIImpl impl) {
        return impl;
    }
}
