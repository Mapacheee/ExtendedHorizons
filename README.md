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

ExtendedHorizons exposes a stable API through Bukkit `ServicesManager`.

Add this to your `plugin.yml`:
```yaml
softdepend: [ExtendedHorizons]
```

Use the API like this:
```java
import me.mapacheee.extendedhorizons.api.ExtendedHorizonsApi;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

RegisteredServiceProvider<ExtendedHorizonsApi> provider =
    Bukkit.getServicesManager().getRegistration(ExtendedHorizonsApi.class);
if (provider != null) {
  ExtendedHorizonsApi api = provider.getProvider();
}
```

Available methods:
- `getPlayerViewDistance(Player player)`
- `setPlayerViewDistance(Player player, int viewDistance)`
- `resetPlayerViewDistance(Player player)`
- `getServerMaxViewDistance()`
- `isFakeChunksEnabled(String worldName)`
- `isFarPlayersEnabled()`

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
