package me.mapacheee.extendedhorizons.commands;

import com.google.inject.Inject;
import com.thewinterframework.command.CommandComponent;
import com.thewinterframework.service.ReloadServiceManager;
import me.mapacheee.extendedhorizons.fakechunks.antixray.AntiXrayService;
import me.mapacheee.extendedhorizons.fakechunks.backend.ChunkSerializationExecutorService;
import me.mapacheee.extendedhorizons.fakechunks.cache.AntiXrayPayloadCacheService;
import me.mapacheee.extendedhorizons.fakechunks.cache.ChunkBuildCacheService;
import me.mapacheee.extendedhorizons.fakechunks.cache.LightPayloadCacheService;
import me.mapacheee.extendedhorizons.fakechunks.FakeChunkOrchestratorService;
import me.mapacheee.extendedhorizons.fakechunks.planner.ChunkPlannerService;
import me.mapacheee.extendedhorizons.fakechunks.session.PlayerSession;
import me.mapacheee.extendedhorizons.fakechunks.session.SessionRegistry;
import me.mapacheee.extendedhorizons.messages.MessagesFacade;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.annotations.suggestion.Suggestions;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.context.CommandInput;
import org.incendo.cloud.paper.util.sender.Source;

import java.util.List;

@CommandComponent
public class EhCommands {

    @Inject
    private ReloadServiceManager reloadServiceManager;
    @Inject
    private ChunkBuildCacheService chunkBuildCacheService;
    @Inject
    private LightPayloadCacheService lightPayloadCacheService;
    @Inject
    private AntiXrayPayloadCacheService antiXrayPayloadCacheService;
    @Inject
    private AntiXrayService antiXrayService;
    @Inject
    private ChunkSerializationExecutorService chunkSerializationExecutorService;
    @Inject
    private FakeChunkOrchestratorService fakeChunkOrchestratorService;
    @Inject
    private SessionRegistry sessionRegistry;
    @Inject
    private MessagesFacade messages;

    private static final int MIN_DISTANCE = 2;

    @Suggestions("online-players")
    public List<String> suggestOnlinePlayers(CommandContext<?> ctx, CommandInput input) {
      var online = Bukkit.getOnlinePlayers();
      String token = input.lastRemainingToken().toLowerCase();
        return online.stream()
            .map(Player::getName)
            .filter(name -> name.toLowerCase().startsWith(token))
            .toList();
    }

    @Command("eh reload")
    @Permission("extendedhorizons.reload")
    public void reloadCommand(Source source) {
        this.reloadServiceManager.reload();
        this.chunkBuildCacheService.rebuildCaches();
        this.chunkBuildCacheService.invalidateAll();
        this.lightPayloadCacheService.rebuild();
        this.lightPayloadCacheService.invalidateAll();
        this.antiXrayPayloadCacheService.rebuild();
        this.antiXrayPayloadCacheService.invalidateAll();
        this.antiXrayService.invalidateAllProfiles();
        this.fakeChunkOrchestratorService.invalidateAllPermissionCache();
        this.chunkSerializationExecutorService.rebuild();
        source.source().sendMessage(this.messages.reloadSuccess());
    }

    @Command("eh setme <distance>")
    @Permission("extendedhorizons.setme")
    public void setMeCommand(Source source, @Argument("distance") int distance) {
        if (!(source.source() instanceof Player sender)) {
            return;
        }
        int clamped = Math.clamp(distance, MIN_DISTANCE, ChunkPlannerService.MAX_CHUNK_DISTANCE);
        PlayerSession session = this.sessionRegistry.ensureFor(sender, false);
        session.playerOverrideDistance(clamped);
        source.source().sendMessage(this.messages.setmeSuccess(clamped));
    }

    @Command("eh set <player> <distance>")
    @Permission("extendedhorizons.set")
    public void setPlayerCommand(
        Source source,
        @Argument(value = "player", suggestions = "online-players") String playerName,
        @Argument("distance") int distance
    ) {
        Player target = Bukkit.getPlayerExact(playerName);
        if (target == null) {
            return;
        }
        int clamped = Math.clamp(distance, MIN_DISTANCE, ChunkPlannerService.MAX_CHUNK_DISTANCE);
        PlayerSession session = this.sessionRegistry.ensureFor(target, false);
        session.playerOverrideDistance(clamped);
        source.source().sendMessage(this.messages.setSuccess(target.getName(), clamped));
        target.sendMessage(this.messages.setNotify(clamped));
    }

    @Command("eh reset [player]")
    @Permission("extendedhorizons.reset")
    public void resetCommand(
        Source source,
        @Argument(value = "player", suggestions = "online-players") String playerName
    ) {
        if (playerName != null) {
            Player target = Bukkit.getPlayerExact(playerName);
            if (target == null) {
                return;
            }
            PlayerSession session = this.sessionRegistry.get(target.getUniqueId());
            if (session != null) {
                session.resetPlayerOverrideDistance();
            }
            source.source().sendMessage(this.messages.resetSuccess(target.getName()));
        } else if (source.source() instanceof Player player) {
            PlayerSession session = this.sessionRegistry.get(player.getUniqueId());
            if (session != null) {
                session.resetPlayerOverrideDistance();
            }
            player.sendMessage(this.messages.resetSelfSuccess());
        } else {
            source.source().sendMessage(this.messages.resetConsoleError());
        }
    }
}
