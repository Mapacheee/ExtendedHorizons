package me.mapacheee.extendedhorizons.shared.service;

import com.google.inject.Inject;
import com.thewinterframework.configurate.Container;
import com.thewinterframework.service.annotation.Service;
import me.mapacheee.extendedhorizons.shared.config.MainConfig;
import me.mapacheee.extendedhorizons.shared.config.MessageConfig;

/*
 * Service for accessing plugin configurations.
 * It provides a centralized and type-safe way to retrieve
 * values from config.yml and messages.yml.
 */
@Service
public class ConfigService {
    private final Container<MainConfig> config;
    private final Container<MessageConfig> messages;

    @Inject
    public ConfigService(Container<MainConfig> config, Container<MessageConfig> messages) {
        this.config = config;
        this.messages = messages;
    }

    public MainConfig get() {
        return config.get();
    }

    public MessageConfig messages() {
        return messages.get();
    }

}
