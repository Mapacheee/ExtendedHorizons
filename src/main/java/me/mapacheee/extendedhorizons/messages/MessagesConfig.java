package me.mapacheee.extendedhorizons.messages;

import com.thewinterframework.configurate.config.Configurate;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Setting;

@ConfigSerializable
@Configurate("messages")
public record MessagesConfig(String prefix, CommandMessages commands, GeneralMessages general) {
  @ConfigSerializable
  public record CommandMessages(
      String reloaded,
      @Setting("player-only") String playerOnly,
      @Setting("setme-updated") String setmeUpdated,
      @Setting("set-updated") String setUpdated,
      @Setting("min-distance") String minDistance,
      @Setting("max-distance") String maxDistance) {}

  @ConfigSerializable
  public record GeneralMessages(@Setting("player-not-found") String playerNotFound) {}
}
