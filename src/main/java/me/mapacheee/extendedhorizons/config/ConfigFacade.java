package me.mapacheee.extendedhorizons.config;

import com.google.inject.Inject;
import com.thewinterframework.configurate.Container;
import com.thewinterframework.service.annotation.Service;

@Service
public final class ConfigFacade {

    private final Container<EhConfig> configContainer;

    @Inject
    public ConfigFacade(Container<EhConfig> configContainer) {
      this.configContainer = configContainer;
    }

    public EhConfig get() {
      return this.configContainer.get();
    }
}

