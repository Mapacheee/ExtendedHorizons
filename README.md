<div align="center">
  <img width="840" alt="ExtendedHorizons banner" src="https://i.imgur.com/cJ3108T.png" />
  <h1>ExtendedHorizons v4</h1>
  <p>Extended view distance for Minecraft servers running Paper or Folia.</p>
  <p>
    <a href="https://github.com/Mapacheee/ExtendedHorizons/releases">Download</a> ·
    <a href="#quick-start">Quick start</a> ·
    <a href="https://discord.gg/yA3vD2S8Zj">Discord</a> ·
    <a href="#hosting-partner">Hosting discount</a>
  </p>
</div>

ExtendedHorizons streams pregenerated or already loaded terrain beyond the server's native view distance. Players use their regular Java client and render distance settings. The server's simulation distance controls which chunks tick.

- Adaptive streaming prioritizes nearby terrain and adjusts to each player's connection.
- Shared chunk snapshots and compressed packets reduce repeated work when players see the same terrain.
- Optional features include distant player tracking, live terrain updates, and Anti-Xray masking.

[Performance benchmarks and tuning guide](docs/performance.md)

## Quick start

**Requires Java 25, Paper or Folia, and MapacheeeLib.** The Paper builds tested for v4 include 1.21.11, 26.1.2, 26.2, and 26.3. [MapacheeeLib releases](https://github.com/Mapacheee/MapacheeeLib/releases)

1. Place the ExtendedHorizons JAR and the matching MapacheeeLib JAR in `plugins/`. [ExtendedHorizons releases](https://github.com/Mapacheee/ExtendedHorizons/releases)
2. Start the server to generate `plugins/ExtendedHorizons/config.yml`.
3. Pregenerate the terrain players will explore. For extended streaming, set the server's native view distance below EH's target `view-distance`, which defaults to 32 chunks.
4. Run `/eh reload` after editing the config.

**Upgrading from v3?** Back up your old config and let v4 generate a fresh one.

## Hosting partner

[![RealisticNode server hosting, 10% off with code EXTENDEDHORIZONS](docs/assets/realisticnode-partner.png)](https://realisticnode.com/extendedhorizons)

Get **10% off server hosting** with code **`EXTENDEDHORIZONS`**. [View RealisticNode plans](https://realisticnode.com/extendedhorizons)

## Configuration and commands

Set `view-distance` for the default target radius. Use `extendedhorizons.max.<distance>` for per-player distances or `extendedhorizons.bypass` to grant the configured maximum. [Configuration and permissions reference](docs/configuration.md)

If Paper Anti-Xray is enabled for a world, configure EH's `anti-xray.enabled` and `anti-xray.hidden-blocks` to allow extended streaming there.

Commands require `extendedhorizons.admin`, which operators have by default.

| Command | Use |
| --- | --- |
| `/eh stats` | Inspect sessions, chunk delivery, and cache counters |
| `/eh reload` | Reload the plugin configuration |
| `/eh farplayers <player>` | Check distant player tracking from your current in-game connection |

## Plugin API

Use the `-api.jar` as a `compileOnly` dependency. In `plugin.yml`, declare `depend: [ExtendedHorizons]`. In `paper-plugin.yml`, declare a server dependency with `load: BEFORE`, `required: true`, and `join-classpath: true`.

```java
import me.mapacheee.extendedhorizons.api.ExtendedHorizonsApi;

// Once the player has an active EH session
var api = getServer().getServicesManager().load(ExtendedHorizonsApi.class);
if (api != null) {
    var playerId = player.getUniqueId();
    api.setViewDistanceLimit(this, playerId, 16);
    // Use getViewDistance(playerId) to inspect the target radius.
    // Use clearViewDistanceLimit(this, playerId) to remove your cap.
}
```

EH applies the lowest cap across plugins and respects native view distance, client settings, and permissions. Caps take effect on the next player tick and expire when the player disconnects or the owning plugin is disabled.

Build both JARs with `./gradlew shadowJar apiJar` or `.\gradlew.bat shadowJar apiJar` on Windows. Find them in `build/libs/`.

## Stats

ExtendedHorizons collects anonymous server metrics through MapacheeeLib and FastStats.

[![Servers & Players](https://www.faststats.dev/embed/default:fec66100-50ff-435f-b9ea-542e113a8312:servers-and-players.svg?w=960&h=340&theme=dark)](https://www.faststats.dev/project/extendedhorizons/dashboards/minecraft-plugin)

## Support

For bug reports, include your server version, plugin list, and relevant logs. [GitHub Issues](https://github.com/Mapacheee/ExtendedHorizons/issues) · [Discord](https://discord.gg/yA3vD2S8Zj)

[Support development via PayPal](https://paypal.me/mapachedou)
