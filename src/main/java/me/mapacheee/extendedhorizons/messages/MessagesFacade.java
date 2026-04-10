package me.mapacheee.extendedhorizons.messages;

import com.google.inject.Inject;
import com.thewinterframework.configurate.Container;
import com.thewinterframework.service.annotation.Service;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

@Service
public final class MessagesFacade {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final Container<EhMessages> messagesContainer;

    @Inject
    public MessagesFacade(Container<EhMessages> messagesContainer) {
        this.messagesContainer = messagesContainer;
    }

    public EhMessages raw() {
        return this.messagesContainer.get();
    }

    public Component reloadSuccess() {
        return parse(this.raw().reloadSuccess());
    }

    public Component setmeSuccess(int distance) {
        return parse(this.raw().setmeSuccess(),
            Placeholder.unparsed("distance", String.valueOf(distance)));
    }

    public Component setSuccess(String playerName, int distance) {
        return parse(this.raw().setSuccess(),
            Placeholder.unparsed("player", playerName),
            Placeholder.unparsed("distance", String.valueOf(distance)));
    }

    public Component setNotify(int distance) {
        return parse(this.raw().setNotify(),
            Placeholder.unparsed("distance", String.valueOf(distance)));
    }

    public Component resetSuccess(String playerName) {
        return parse(this.raw().resetSuccess(),
            Placeholder.unparsed("player", playerName));
    }

    public Component resetSelfSuccess() {
        return parse(this.raw().resetSelfSuccess());
    }

    public Component resetConsoleError() {
        return parse(this.raw().resetConsoleError());
    }

    private static Component parse(String message, TagResolver... resolvers) {
        if (message == null || message.isEmpty()) {
            return Component.empty();
        }
        return MINI_MESSAGE.deserialize(message, resolvers);
    }
}
