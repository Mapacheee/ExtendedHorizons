package me.mapacheee.extendedhorizons.messages;

import com.thewinterframework.configurate.config.Configurate;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Setting;

@ConfigSerializable
@Configurate("messages")
public record EhMessages(
    @Setting("reload-success") String reloadSuccess,
    @Setting("setme-success") String setmeSuccess,
    @Setting("set-success") String setSuccess,
    @Setting("set-notify") String setNotify,
    @Setting("reset-success") String resetSuccess,
    @Setting("reset-self-success") String resetSelfSuccess,
    @Setting("reset-console-error") String resetConsoleError
) {}