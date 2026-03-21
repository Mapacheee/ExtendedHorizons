package me.mapacheee.extendedhorizons.config;

import com.google.inject.Inject;
import com.thewinterframework.configurate.Container;
import com.thewinterframework.service.annotation.Service;
import me.mapacheee.extendedhorizons.messages.MessagesConfig;

@Service
public class ConfigService {

    private final Container<Config> configContainer;
    private final Container<MessagesConfig> messagesConfigContainer;

    @Inject
    public ConfigService(Container<Config> configContainer, Container<MessagesConfig> messagesConfigContainer) {
        this.configContainer = configContainer;
        this.messagesConfigContainer = messagesConfigContainer;
    }

    public Config get() {
        return configContainer.get();
    }

    public MessagesConfig messages() {
        return messagesConfigContainer.get();
    }
}
