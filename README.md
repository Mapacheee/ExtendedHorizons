<div align="center">
  <img width="1080" height="462" alt="eh_main(1)(1)" src="https://i.imgur.com/cJ3108T.png" />
</div>

---
## What is ExtendedHorizons?

ExtendedHorizons is a high-performance view-distance extension plugin for modern Paper/Folia servers.  
It renders distant terrain using optimized fake chunks and optional far-player sync, so players can see farther than vanilla without the usual server overhead.

---
## Dependencies
- [PlaceholderAPI](https://github.com/PlaceholderAPI/PlaceholderAPI) *(optional)*
- [MapacheeeLib](https://github.com/Mapacheee/MapacheeeLib)

---
## How to build

Build this project with this command: 
```cmd
./gradlew shadowJar
```
The artifact will be generated in `build/libs/ExtendedHorizons-{version}.jar` ready to use!

---
## For developers

ExtendedHorizons is built entirely with WinterFramework (Guice), so you can inject its services directly or resolve them statically.

Add this to your `plugin.yml` or `paper-plugin.yml`:
```yaml
depend:
  - ExtendedHorizons
```

### Accessing Services

You can access services natively through the Guice Injector provided by WinterFramework:

```java
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import me.mapacheee.extendedhorizons.config.ConfigFacade;
import me.mapacheee.extendedhorizons.fakechunks.session.SessionRegistry;
import org.bukkit.plugin.java.JavaPlugin;

ExtendedHorizonsPlugin eh = JavaPlugin.getPlugin(ExtendedHorizonsPlugin.class);

// Retrieve via standard Guice Injector:
SessionRegistry sessionRegistry = eh.getInjector().getInstance(SessionRegistry.class);

// Or via the static helper:
sessionRegistry = ExtendedHorizonsPlugin.getService(SessionRegistry.class);
```

### What you can do through these services:

- **Override view distance:** Set a custom radius for specific players dynamically.
  ```java
  PlayerSession session = sessionRegistry.ensureFor(player, false);
  session.playerOverrideDistance(64); // See up to 64 fake chunks
  ```
- **Reset view distance:** Clear the custom override and allow the plugin to fall back to `world-settings` or `permissions`.
  ```java
  PlayerSession session = sessionRegistry.get(player.getUniqueId());
  if (session != null) {
      session.resetPlayerOverrideDistance();
  }
  ```
- **Read configuration:** Access world rules, safe factors, and far-player settings dynamically via `configFacade.get()`.
- **Track Far Players:** ExtendedHorizons natively syncs far players. You can read the `trackedFarPlayers()` from a `PlayerSession` to see exactly which entities are being simulated locally.

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
