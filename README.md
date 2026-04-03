<div align="center">
  <img width="1080" height="462" alt="eh_main(1)(1)" src="https://i.imgur.com/cJ3108T.png" />
</div>

---
## What is ExtendedHorizons?

ExtendedHorizons is a high-performance view-distance extension plugin for modern Paper/Folia servers.  
It renders distant terrain using optimized fake chunks and optional far-player sync, so players can see farther than vanilla without the usual server overhead.

---
## Dependencies
- [PacketEvents](https://github.com/retrooper/packetevents)
- [PlaceholderAPI](https://github.com/PlaceholderAPI/PlaceholderAPI) *(optional)*
- [TheWinterFramework](https://github.com/thewinterframework/winter) *(shaded in artifact)*

---
## How to build

Build this project with this command: 
```cmd
./gradlew shadowJar
```
The artifact will be generated in `build/libs/ExtendedHorizons-{version}.jar` ready to use!

---
## For developers

You can access ExtendedHorizons services directly via injector.

Add this to your `plugin.yml`:
```yaml
softdepend: [ExtendedHorizons]
```

Resolve services like this:
```java
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import me.mapacheee.extendedhorizons.chunk.FakeChunkService;
import me.mapacheee.extendedhorizons.config.ConfigService;
import org.bukkit.plugin.java.JavaPlugin;

ExtendedHorizonsPlugin eh = JavaPlugin.getPlugin(ExtendedHorizonsPlugin.class);
FakeChunkService fakeChunkService = eh.getInjector().getInstance(FakeChunkService.class);
ConfigService configService = eh.getInjector().getInstance(ConfigService.class);
```

You can find a ready-to-use integration example in the `examples` directory.

What you can do through these services:
- Get the advertised distance for a player with `FakeChunkService#getAdvertisedDistance(UUID)`.
- Apply or override player distance with `FakeChunkService#applyDistancePreference(Player, int)`.
- Trigger fake-chunk refresh for changed chunks with `FakeChunkService#handleRealChunkInteraction(World, int, int)` or `FakeChunkService#handleRealChunkInteraction(UUID, int, int)`.
- Read runtime config with `ConfigService#get()`.
- Check feature flags from config like `fakeChunksEnabledForWorld(worldName)` and `farPlayersEnabled()`.

---
## Contribute
To contribute to this project, just follow this steps:
- Fork repository.
- Make ur changes.
- Make sure your changes work.
- Create a pull request explaining what you've done!

Every contribution is welcome and appreciated!

---
## Support
- Report issues and suggestions in the repository’s issues section.
- Join our Discord: [discord.gg/yA3vD2S8Zj](https://discord.gg/yA3vD2S8Zj)
- Consider donate: [PayPal](https://paypal.me/mapachedou)
