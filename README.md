<div align="center">
  <img width="1024" height="447" alt="Gemini_Generated_Image_knhvbtknhvbtknhv" src="https://github.com/user-attachments/assets/ed7bf619-3729-4ebf-afa7-ec543bbd9f73" />
</div>

<div align="center">
  <img width="1800" height="2000" alt="fondo" src="https://github.com/user-attachments/assets/87ac01de-8852-4c8a-b3bb-81982d10f7a0" />
</div>
<div align="center">
  <img width="1920" height="991" alt="2025-10-25_09 24 09" src="https://github.com/user-attachments/assets/f09f418f-340a-487d-8a8c-d169601dc1fc" />
</div>
<div align="center">
  <img width="1920" height="991" alt="2025-10-26_09 03 15(1)(1)" src="https://github.com/user-attachments/assets/3454b354-996a-4ced-893a-4d70cb7f105e" />
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

## Config

```yml
# Global view distance limits and defaults
view-distance:
  max-distance: 64
  min-distance: 2
  default-distance: 32

# Performance settings
performance:
  # Maximum chunks to load per tick (for async loading)
  max-chunks-per-tick: 20

  # Fake chunks system (packets cache)
  fake-chunks:
    # Enable packet cache system
    enabled: true
    # Maximum cached packets (5000 = ~150MB)
    max-cached-packets: 5000
    # Enable GZIP compression for packets (slower but saves RAM)
    use-compression: false
    # Cache cleanup interval in seconds
    cache-cleanup-interval: 20

# Database (SQLite) used for player view persistence
database:
  enabled: true
  file-name: "extendedhorizons"

integrations:
  placeholderapi:
    enabled: true
  luckperms:
    enabled: true
    check-interval: 60 # seconds
    use-group-permissions: true

# Message toggles (actual texts live in messages.yml)
messages:
  welcome-message:
    enabled: true
```
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

### LuckPerms Integration
If `integrations.luckperms.enabled` is true, the plugin will check limits per group/player.  
You can combine it with `use-group-permissions` and your group policies.

---

## Placeholders (PlaceholderAPI)
- `%extendedhorizons_view_distance%` — current effective distance

---

## Operation
- Distance is managed per player with global limits configured in `config.yml`
- **Dual chunk system:**
    - **Real chunks** (within server view-distance): Managed naturally by the server
    - **Fake chunks** (beyond server view-distance): Sent via packet cache when `fake-chunks.enabled: true`
- The server's view-distance (from `server.properties`) acts as the boundary between real and fake chunks
- All chunk processing is done **100% asynchronously** to maintain server performance
- **LRU cache system** automatically manages memory with configurable limits
- PacketEvents is **required**
- Fully compatible with **Paper 1.21+**

---
# Support
- Report issues and suggestions in the repository’s Issues section.
- Join our **Discord**: [discord.gg/yA3vD2S8Zj](https://discord.gg/yA3vD2S8Zj)
---
<div align="center">
  <img width="1920" height="578" alt="photo-collage png(1)(1)" src="https://github.com/user-attachments/assets/db8c8477-4964-4466-8b01-9c4ed3a6d0a2" />
</div>
