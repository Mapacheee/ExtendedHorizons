package me.mapacheee.extendedhorizons.hooks.placeholderapi;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import com.thewinterframework.service.annotation.lifecycle.OnDisable;
import com.thewinterframework.service.annotation.lifecycle.OnEnable;
import me.mapacheee.extendedhorizons.fakechunks.session.SessionRegistry;
import org.bukkit.Bukkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public final class PapiHookService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PapiHookService.class);

    private final SessionRegistry sessionRegistry;
    private Object expansion;

    @Inject
    public PapiHookService(SessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
    }

    @OnEnable
    public void onEnable() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                this.expansion = new InternalPapiExpansion(this.sessionRegistry);
                ((InternalPapiExpansion) this.expansion).register();
                LOGGER.info("Registered PlaceholderAPI expansion.");
            } catch (Throwable t) {
                LOGGER.debug("Failed to register PlaceholderAPI expansion.", t);
            }
        } else {
            LOGGER.debug("PlaceholderAPI not found. Skipping hook registration.");
        }
    }

    @OnDisable
    public void onDisable() {
        if (this.expansion != null) {
            try {
                ((InternalPapiExpansion) this.expansion).unregister();
                this.expansion = null;
            } catch (Throwable t) {
                LOGGER.debug("Failed to unregister PlaceholderAPI expansion.", t);
            }
        }
    }
}
