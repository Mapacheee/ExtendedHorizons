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
########################################################
#                                                      #
#         ExtendedHorizons V2 Configuration            #
#                                                      #
#          Support the project starring it!            #
#     https://github.com/Mapacheee/ExtendedHorizons    #
#                                                      #
#   Join our Discord:  https://discord.gg/yA3vD2S8Zj   #
#                                                      #
########################################################

# Global view distance limits and defaults
# Note: The minimum distance is automatically set to the server's view-distance from server.properties
view-distance:
  max-distance: 64
  default-distance: 32

# Per-world settings (optional)
# If a world is not listed here, it will use the global settings above
# You can configure each world independently
world-settings:
  # Example: Limit view distance in the Nether
  # world_nether:
  #   enabled: true           # Enable/disable fake chunks for this world
  #   max-distance: 32        # Maximum view distance for this world
  
  # Example: Disable fake chunks in the End
  # world_the_end:
  #   enabled: false
  #   max-distance: 64

# Performance settings
performance:
  # Number of threads for parallel chunk processing (0 = auto-detect based on CPU cores)
  # Recommended: 0 (auto) or 4-8 for most servers
  chunk-processor-threads: 0
  # Interval in ticks between chunk validation/process cycles
  # Higher values reduce CPU usage but make loading "stuttery"
  chunk-process-interval: 1
  
  # Teleport warmup delay in milliseconds to load fake chunks
  teleport-warmup-delay: 10

  # MSPT Protection: Pause fake chunk loading if server is lagging
  max-mspt-for-loading: 40.0

  # Async Task Throttling: Limit concurrent chunk loading tasks
  max-async-load-tasks: 4
  max-async-load-queue: 10

  # Generation Limiter: Strict limit on chunks generated per tick
  max-generations-per-tick: 1

  # Fake chunks system (packets cache)
  fake-chunks:
    # Enable packet cache system
    enabled: true
    # Maximum cached packets (5000 = ~150MB)
    max-cached-packets: 5000
    # Enable GZIP compression for packets (slower but saves RAM)
    use-compression: false
    # Cache cleanup interval in seconds
    cache-cleanup-interval: 10
    # Enable chunk memory cache (reuses loaded chunks, saves CPU but uses RAM)
    # Recommended: true for <50 players, false for >100 players or low RAM servers
    enable-memory-cache: false
    # Maximum chunks to cache in memory (1000 = ~40-80MB depending on chunk complexity)
    # Increase for more players in same area, decrease for dispersed players or low RAM
    max-memory-cache-size: 1000
    # Packet cache TTL in seconds
    packet-cache-ttl-seconds: 30

    # Enable this feature will affect the speed to load fake chunks
    # enable this only if its necessary
    anti-xray:
      # Enable anti-xray obfuscation for fake chunks
      enabled: false
      # Replace valuable ores with stone/deepslate
      hide-ores: true
      # Add fake ores to confuse x-ray users
      add-fake-ores: true
      # Density of fake ores (0.0-1.0, higher = more fakes)
      fake-ore-density: 0.15
  
  # Occlusion Culling: Prevents sending hidden chunks to save bandwidth/FpS
  occlusion-culling:
    # Enable occlusion culling (recommended: true)
    enabled: true
    # Chunks below this sky light level may be culled (hidden)
    # 0 = nothing culled based on light, 15 = strict
    sky-light-threshold: 14
    # Maximum Y level to cull (usually matches server max height)
    max-y-level: 320
    # Minimum Y level to cull (usually matches server min height)
    min-y-level: -64

# Bandwidth Saver settings
bandwidth-saver:
  enabled: true
  # Skips redundant entity movement/rotation packets (ESU-inspired)
  skip-redundant-packets: true
  # Limits the rate of fake chunk sending to prevent network saturation
  max-fake-chunks-per-tick: 20
  # Maximum bandwidth per player in KB/s (512 KB/s = ~4 Mbps)
  # Set to 0 to disable bandwidth limiting
  max-bandwidth-per-player: 10000
  # Enable adaptive rate limiting based on player ping
  # Reduces packet rate for high-ping players to prevent packet loss
  adaptive-rate-limiting: true
  # Use real packet size measurement (slightly more CPU, very accurate)
  # Recommended: true for accurate bandwidth tracking
  measure-actual-packet-size: true
  # Fallback estimate if measurement disabled (in bytes)
  # Average chunk packet is ~50KB (mountains can be 100KB+)
  estimated-packet-size: 50000

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
    enabled: false
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
- `extendedhorizons.max.<distance>` — set max distance for a player

### LuckPerms Integration
If `integrations.luckperms.enabled` is true, the plugin will check limits per group/player.  
You can combine it with `use-group-permissions` and your group policies.

---

## Placeholders (PlaceholderAPI)
- `%extendedhorizons_view_distance%` — current effective distance

---

## Operation
- Distance is managed per player with global limits configured in `config.yml`
- **Hybrid Chunk System:**
    - **Real chunks** (within server view-distance): Managed naturally by the server.
    - **Extended chunks** (beyond server view-distance): Retrieved directly from NMS memory (Zero-Latency) and sent via a custom async pipeline.
- **Crash Prevention Architecture**:
    - **Main-Thread Packet Creation**: Eliminates race conditions.
    - **Strict Throttling**: `max-fake-chunks-per-tick` prevents OOM and bandwidth saturation.
    - **World-Switch Safety**: Prevents protocol errors when teleporting between dimensions.
- All chunk processing is done **100% asynchronously** (except critical NMS access) to maintain server performance.
- PacketEvents is **required** for packet injection.
- Fully compatible with **Paper, Folia, and ViaVersion**.

---
# Support
- Report issues and suggestions in the repository’s Issues section.
- Join our **Discord**: [discord.gg/yA3vD2S8Zj](https://discord.gg/yA3vD2S8Zj)
---
<div align="center">
  <img width="1920" height="578" alt="photo-collage png(1)(1)" src="https://github.com/user-attachments/assets/db8c8477-4964-4466-8b01-9c4ed3a6d0a2" />
</div>
