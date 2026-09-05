package me.mapacheee.extendedhorizons.fakechunks.listener;

import java.lang.reflect.Proxy;
import java.util.UUID;
import me.mapacheee.extendedhorizons.TestContainers;
import me.mapacheee.extendedhorizons.config.EhConfig;
import me.mapacheee.extendedhorizons.fakechunks.FakeChunkOrchestratorService;
import me.mapacheee.extendedhorizons.fakechunks.netty.ChannelInjectionService;
import me.mapacheee.extendedhorizons.fakechunks.session.SessionRegistry;
import org.bukkit.World;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlayerLifecycleListenerTest {
    @Test void shortTeleportPreservesVanillaTracking() {
        UUID worldId = UUID.randomUUID(), playerId = UUID.randomUUID();
        World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class[]{World.class},
            (proxy, method, args) -> method.getName().equals("getUID") ? worldId : null);
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class[]{Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> playerId;
                case "getWorld" -> world;
                default -> null;
            });
        var config = TestContainers.containing(EhConfig.empty());
        var sessions = new SessionRegistry();
        var injection = new ChannelInjectionService();
        var orchestrator = new FakeChunkOrchestratorService(config, sessions, null, injection, null, null);
        var listener = new PlayerLifecycleListener(sessions, injection, null, orchestrator, null, config);
        var session = sessions.ensureFor(player, false);
        session.setChunkPos(0, 0); session.updateDistance(8); session.enabled(true);
        session.serverChunkAdd(0, 0); session.addServerTrackedEntity(42);
        long epoch = session.epoch();
        listener.onTeleport(new PlayerTeleportEvent(player, new Location(world, 0, 64, 0),
            new Location(world, 1, 64, 0)));
        assertEquals(epoch, session.epoch());
        assertTrue(session.isServerTrackingEntity(42));
        assertNotEquals(0L, session.pollNextChunkKey());
    }
}
