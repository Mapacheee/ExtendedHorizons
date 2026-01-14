<div align="center">
  <img width="1080" height="462" alt="eh_main(1)(1)" src="https://github.com/user-attachments/assets/e11ddfe6-3c45-4eac-9d3b-c91ea8596cca" />
</div>
<div align="center">
  <img width="1080" height="2000" alt="eh_main(1)(1)" src="https://github.com/user-attachments/assets/e3a88d21-42f6-42b3-834b-e1ef0f968deb" />
</div>
<div align="center">
  <img width="1080" height="557" alt="eh_main(2)(2)" src="https://github.com/user-attachments/assets/29235835-66bc-431c-8480-eae557b4e04e" />
</div>

---

## Dependencies
- [PacketEvents](https://github.com/retrooper/packetevents)
- [LuckPerms](https://luckperms.net/) *(optional)*
- [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) *(optional)*

---

## Installation
1. Download **ExtendedHorizons.jar**
2. Download dependencies
3. Put all of them inside your `/plugins` folder
4. Start your server — done

---
## Messages
- All texts are in **messages.yml**, with MiniMessage support.
- The welcome message is controlled by `messages.welcome-message.enabled` in **config.yml**, and its text is in **messages.yml**.

---

## Commands
Alias base: `/eh` (also: `extendedhorizons`, `horizons`, `viewdistance`, `vd`)

| Command | Description | Permission |
|----------|--------------|-------------|
| `/eh help` | General help | `extendedhorizons.use` |
| `/eh info` | Plugin information and your current distance | `extendedhorizons.use` |
| `/eh view` | Shows your current distance | `extendedhorizons.use` |
| `/eh setme <distance>` | Sets your distance | `extendedhorizons.use` |
| `/eh reset` | Resets your distance to default | `extendedhorizons.use` |
| `/eh check <player>` | Checks another player's distance | `extendedhorizons.admin` |
| `/eh setplayer <player> <distance>` | Sets another player's distance | `extendedhorizons.admin` |
| `/eh resetplayer <player>` | Resets another player's distance | `extendedhorizons.admin` |
| `/eh reload` | Reloads settings | `extendedhorizons.admin` |
| `/eh stats` | Displays statistics | `extendedhorizons.admin` |

---

## Permissions
- `extendedhorizons.use` — player commands
- `extendedhorizons.admin` — admin commands
- `extendedhorizons.bypass.limits` — ignores boundaries when setting distances
- `extendedhorizons.max.<distance>` — set max distance for a player

### LuckPerms Integration
If `integrations.luckperms.enabled` is true, the plugin will check limits per group/player.  
You can combine it with `use-group-permissions` and your group policies.

---

## Placeholders (PlaceholderAPI)
- `%extendedhorizons_view_distance%` — current effective distance

---

## API for Developers

ExtendedHorizons provides a comprehensive API for other plugins to interact with fake chunks.

### Documentation
- **[Full API Documentation](API-USAGE.md)** - Complete guide with examples
- **[Example Plugin](examples/)** - Working example showing all API features

### Quick Example
```java
// Access the API
ExtendedHorizonsAPI api = ExtendedHorizonsPlugin.getService(ExtendedHorizonsAPI.class);

// Check if player is looking at a fake chunk
int chunkX = player.getLocation().getBlockX() >> 4;
int chunkZ = player.getLocation().getBlockZ() >> 4;
if (api.isFakeChunk(player, chunkX, chunkZ)) {
    player.sendMessage("You're in a fake chunk!");
}

// Get all fake chunks for a player
Set<ChunkCoordinate> chunks = api.getFakeChunksForPlayer(player);
player.sendMessage("Loaded: " + chunks.size() + " fake chunks");
```

### Available Events
- `FakeChunkLoadEvent` - When a fake chunk is loaded (cancellable)
- `FakeChunkUnloadEvent` - When a fake chunk is unloaded
- `FakeChunkBatchLoadEvent` - When multiple chunks are loaded at once

See **[API-USAGE.md](API-USAGE.md)** for complete documentation.

---
# Support
- Report issues and suggestions in the repository’s Issues section.
- Join our **Discord**: [discord.gg/yA3vD2S8Zj](https://discord.gg/yA3vD2S8Zj)
---
<div align="center">
  <img width="1920" height="578" alt="photo-collage png(1)(1)" src="https://github.com/user-attachments/assets/db8c8477-4964-4466-8b01-9c4ed3a6d0a2" />
</div>
