<div align="center">
  <img width="1024" height="447" alt="Gemini_Generated_Image_knhvbtknhvbtknhv" src="https://github.com/user-attachments/assets/ed7bf619-3729-4ebf-afa7-ec543bbd9f73" />
</div>

<div align="center">
  <img width="1800" height="2000" alt="fondo" src="https://github.com/user-attachments/assets/87ac01de-8852-4c8a-b3bb-81982d10f7a0" />
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
# Plugin toggles
general:
  enabled: true
  debug: false

# Global view distance limits and defaults
view-distance:
  max-distance: 64       # Global hard cap
  min-distance: 2        # Global minimum
  default-distance: 16   # Default for new players
  enable-fake-chunks: true           # Allow sending client-only (fake) chunks
  fake-chunks-start-distance: 10     # Start using fake chunks above this distance

# Throughput / pacing
performance:
  max-chunks-per-tick: 5   # Per-tick cap used by sender

# Network limits
network:
  max-bytes-per-second-per-player: 1048576  # 1 MB/s, 0 = unlimited

# Per-world overrides (add blocks named exactly as your world names)
worlds:
  default:
    enabled: true
    max-distance: 64
    fake-chunks-enabled: true
  # Example world override (uncomment and adjust):
  # world:               # exact world name
  #   enabled: true
  #   max-distance: 48
  #   fake-chunks-enabled: true

# Fake chunks cache settings
fake-chunks:
  cache-size: 64

# Database (SQLite) used for player view persistence
database:
  enabled: true
  file-name: "extendedhorizons"

# Integrations
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
- All texts are in **messages.yml**, with HEX color support: ``&#RRGGBB``  
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
| `/eh debug` | Toggles/checks debug mode | `extendedhorizons.admin` |
| `/eh worldinfo <world>` | Displays the maximum configured for a world | `extendedhorizons.admin` |
| `/eh worldhelp` | Configuration help per world | `extendedhorizons.admin` |

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
- `%extendedhorizons_distance%` — current effective distance  
- `%extendedhorizons_max_distance%` — maximum allowed (world/permission dependent)  
- `%extendedhorizons_target_distance%` — target distance  

---

## Operation
- Distance is managed per player, with global and per-world limits.  
- Fake chunks are sent to the client when the target distance exceeds `fake-chunks-start-distance` and if enabled globally and per world.  
- PacketEvents is **required** (not included in the JAR).  
- Fully compatible with **Paper** and **Folia**; Folia detection can be disabled.  

---

## Build
```bat
./gradlew.bat clean shadowJar
```
The JAR is generated in `build/libs/`.

---
# Support
- Report issues and suggestions in the repository’s Issues section.
- Join our **Discord**: [discord.gg/yA3vD2S8Zj](https://discord.gg/yA3vD2S8Zj)
---
<div align="center">
  <img width="1920" height="578" alt="photo-collage png(1)(1)" src="https://github.com/user-attachments/assets/db8c8477-4964-4466-8b01-9c4ed3a6d0a2" />
</div>


