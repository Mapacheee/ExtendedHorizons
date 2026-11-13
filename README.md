<div align="center">
  <img width="1080" height="462" alt="ExtendedHorizons Banner" src="https://i.imgur.com/cJ3108T.png" />

  <h1>ExtendedHorizons v4</h1>
  <p><strong>Next-generation view distance extension for modern Paper servers</strong></p>

  <p>
    <a href="https://github.com/Mapacheee/ExtendedHorizons/releases"><img alt="GitHub Release" src="https://img.shields.io/github/v/release/Mapacheee/ExtendedHorizons?include_prereleases&style=for-the-badge&color=blue" /></a>
    <a href="https://discord.gg/yA3vD2S8Zj"><img alt="Discord" src="https://img.shields.io/badge/Discord-Join%20Us-5865F2?style=for-the-badge&logo=discord&logoColor=white" /></a>
    <a href="https://faststats.dev/project/extendedhorizons/minecraft-plugin"><img alt="FastStats" src="https://img.shields.io/badge/FastStats-Metrics-4c1?style=for-the-badge" /></a>
    <a href="https://paypal.me/mapachedou"><img alt="Donate" src="https://img.shields.io/badge/Donate-PayPal-00457C?style=for-the-badge&logo=paypal&logoColor=white" /></a>
  </p>
</div>

---

## What is ExtendedHorizons?

**ExtendedHorizons** is a view distance extension plugin engineered specifically for modern Minecraft Paper & Folia servers. It streams distant terrain far beyond the server's native simulation and view distances by loading pre-generated chunks directly from disk or capturing chunks already active in memory.

Clients receive terrain snapshots without keeping distant chunks loaded for simulation. Reading, preparing and sending those snapshots still consumes CPU, memory and bandwidth.

> [!NOTE] 
> - **Shared chunk reads & compression cache:** Compressed packet data is cached and shared across players viewing identical terrain.
> - **Direction-aware delivery:** Prioritizes chunks along the player's vector of travel, smoothly filling surrounding terrain when stationary.
> - **Native Anti-Xray integration:** Custom block masking in distant chunks to protect ores without leaking underground data.
> - **Far-player tracking:** Optional distant player rendering and synchronization beyond vanilla tracking ranges.

---

## Performance Comparison (Vanilla vs v4)

Measured on **November 12, 2026**, using **Paper 26.3 build 175**, **50 simulated Java clients**, a 6 GiB maximum server heap and the same pregenerated world. Both variants received **90 seconds of initial loading**, followed by **35 seconds of flight**. Paper alone uses a native view distance of **32**; EH uses a native distance of **4** and extends visibility to **32**. Simulation distance is **2** and Netty uses **4 threads** in both runs. EH uses the new **256 MiB / 30-second cache defaults** and a **192 MiB/s** send budget.

| Metric | Paper only (native radius 32) | Paper + EH v4 (visible radius 32) |
| :--- | :---: | :---: |
| **Terrain Coverage (end of flight)** | 96.3% | 99.7% |
| **Network Traffic (flight)** | 454 MiB | 1,010 MiB |
| **Mean Server CPU** | 32.2% | 28.0% |
| **Mean Used Java Heap (flight)** | 4,503 MiB | 889 MiB |
| **Server Tick Duration (p95)** | 119.5 ms | 27.3 ms |
| **Local RTT (p95)** | 966.7 ms | 631.8 ms |
| **Median Time to 95% Coverage during arrival** | 56.1 s | 36.3 s |
| **Median Flight Speed** | 58.1 blocks/s | 59.0 blocks/s |
| **Position Corrections during flight** | 0 | 0 |

These are single runs with clustered players, the server and clients sharing one Windows machine with an AMD Ryzen 7 5800H and 16 logical processors. CPU is the server process's share of total host CPU capacity; traffic includes all clientbound data. RTT uses protocol ping responses over localhost. Coverage counts chunks received within the same 32-chunk target, rather than client rendering performance. Mean used heap includes temporary allocations and is not a measurement of retained memory. Mob spawning, weather changes and random ticks were disabled; optional EH features were disabled for these performance measurements and checked separately.

Both variants completed initial loading and maintained the requested flight without position corrections. EH sent more data in this comparison, and RTT remained high with all 50 players flying close together. These measurements do not establish a universal CPU, latency or network advantage, and distant snapshots do not provide the full simulation of native chunks. The EH run logged external Mojang discovery and Paper update-check DNS failures during startup; no chunk delivery errors occurred during the measured flight.

---

##  Requirements

- **Java 25**
- **Paper** (Tested across 1.21.11, 26.1.2, 26.2, and 26.3)
- **[MapacheeeLib](https://github.com/Mapacheee/MapacheeeLib/releases)** (Required dependency, install alongside EH)
- **[WorldEdit](https://enginehub.org/worldedit)** or **[FastAsyncWorldEdit (FAWE)](https://github.com/IntellectualSites/FastAsyncWorldEdit/releases)** *(Optional, for real-time terrain invalidation on bulk edits)*

**Folia dependency limitation:** Streaming and packet integrity passed on Folia 26.3, but Winter Paper 3.0.0 throws `UnsupportedOperationException` during shutdown when cancelling Bukkit scheduler tasks. This also occurs with the previous EH build and requires a dependency fix for a clean shutdown.

> [!IMPORTANT]
> - **Pregenerated world recommended:** ExtendedHorizons serves available terrain; it does **not** generate new chunks on the fly to protect TPS.
> - **Client view distance:** A player's in-game render distance setting caps what the client renders.

---

## Installation & Upgrading

1. Download the latest `ExtendedHorizons-<version>.jar` and matching `MapacheeeLib` from [Releases](https://github.com/Mapacheee/ExtendedHorizons/releases).
2. Place both JAR files in your server's `plugins/` directory.
3. Start the server to generate configuration files.
4. Tweak `plugins/ExtendedHorizons/config.yml` to your liking and run `/eh reload`.

> [!WARNING]
> **Upgrading from v3:** Back up your previous configuration and generate a clean `config.yml`. The configuration schema has been redesigned for v4.

---

## Configuration Reference

A snippet of key settings in `config.yml`:

| Setting | Default | Description |
| :--- | :---: | :--- |
| `view-distance` | `32` | Target view radius in chunks (includes native server distance). |
| `disabled-worlds` | `[]` | List of world names where extended chunks are disabled. |
| `player-mib-per-second` | `8` | Maximum uncompressed chunk packet bandwidth per player per second. |
| `global-mib-per-second` | `192` | Global server-wide bandwidth budget for chunk streaming. |
| `workers` | `2` | Background worker threads for chunk reading and processing. |
| `cache-mib` | `256` | Snapshot cache budget in MiB. The compressed packet cache has an additional budget of one quarter of this value. |
| `cache-seconds` | `30` | Maximum age of a cached snapshot. Enabled chunk updates invalidate edited snapshots before this expires. |
| `far-players.enabled` | `false` | Renders distant players in fake chunks (with equipment and action support). |
| `chunk-updates.enabled` | `false` | Dynamically updates fake chunks on block break, place, growth, and physics. |
| `chunk-updates.worldedit`| `false` | Listens to WorldEdit / FAWE commits to update distant terrain after large edits. |
| `anti-xray.enabled` | `false` | Obfuscates specified ores/blocks in extended chunks. |
| `anti-xray.hidden-blocks`| *Ore list* | List of block IDs to mask out in fake chunk packets. |

> [!TIP]
> **Anti-Xray Notice:** If Paper's engine Anti-Xray is active for a world, ExtendedHorizons requires its own `anti-xray.enabled: true` with a configured block list to send extended chunks in that world.

### Streaming performance

For pregenerated terrain at a 32-chunk target, start with `cache-mib: 256`, `cache-seconds: 30`, `workers: 2` and the default send limits. Existing configuration values are preserved when upgrading; change these settings explicitly and run `/eh reload` to adopt the new cache defaults. Keep your far-player, chunk-update, WorldEdit and Anti-Xray settings enabled as needed.

For a faster initial load, test `global-mib-per-second: 256` if CPU and network capacity permit. In the local 50-client test with players spread across the world, this reduced median arrival time to 95% coverage from **36.3–36.7 seconds to 28.6 seconds**, with **99.99%** final flight coverage. This is a tested tuning option rather than the default, and does not predict performance on another host.

Use the counter changes in `/eh stats` over a fixed interval to guide tuning:

- If `diskReads` and `loadedCaptures` keep increasing rapidly while players revisit the same terrain, increase `cache-mib` if heap capacity permits. Increasing the TTL helps reuse snapshots that would otherwise expire; live edits require chunk updates to be enabled.
- If source preparation is keeping up but delivery is repeatedly `throttled`, the send budget may be limiting initial loading. Increasing `global-mib-per-second` can trade more CPU and network activity for faster delivery. Both bandwidth settings count **uncompressed packet bytes**, so monitor actual outbound traffic as well.
- Increase `workers` only when cache misses keep source preparation busy and the machine has spare CPU. Additional workers do not increase the send budget or remove the cost of native player tracking.
- If the network event loop is backed up, increasing the send budget can make latency worse even while TPS remains healthy. Check RTT, actual movement and delivery coverage together.

Paper's [`settings.netty-threads`](https://docs.papermc.io/paper/reference/spigot-configuration/) is a separate server setting. In the local clustered-player test, increasing it from 4 to 8 reduced RTT p95 from 629 to 60 ms, while server CPU rose from 28.4% to 40.2% and tick duration p95 rose from 25.1 to 59.0 ms. The comparison above keeps 4 threads; increasing this value needs its own CPU and tick-time measurements.

Snapshot and compression cache budgets do not cap the plugin's total memory use. Temporary buffers, active reads, connection state and the server's native chunks also require memory.

---

## Commands & Permissions

All commands require the `extendedhorizons.admin` permission (granted to OPs by default).

| Command | Description |
| :--- | :--- |
| `/eh` or `/eh stats` | Shows session count, delivery performance, chunk source stats, and far players status. |
| `/eh farplayers <player>` | Inspects tracking details for a distant player from your current connection (in-game only). |
| `/eh reload` | Reloads `config.yml` on the fly. |

---

## Building from Source

ExtendedHorizons is built with [TheWinterFramework](https://github.com/thewinterframework/winter) and Gradle:

```bash
# Clone the repository
git clone https://github.com/Mapacheee/ExtendedHorizons.git
cd ExtendedHorizons

# Build fat JAR with Shadow
./gradlew shadowJar
```

*(On Windows PowerShell / CMD, use `.\gradlew.bat shadowJar`)*

The output JAR will be available in `build/libs/ExtendedHorizons-<version>.jar`.

---

## Metrics

ExtendedHorizons collects anonymous metrics via FastStats (integrated through MapacheeeLib).  
You can view global stats at the **[FastStats Dashboard](https://faststats.dev/project/extendedhorizons/minecraft-plugin)**.

[![Servers & Players](https://www.faststats.dev/embed/default:fec66100-50ff-435f-b9ea-542e113a8312:servers-and-players.svg?w=960&h=340&theme=dark)](https://www.faststats.dev/project/extendedhorizons/dashboards/minecraft-plugin)

---

## Contributing & Support

- **Bug Reports & Feature Requests:** Open an issue on our [GitHub Issues](https://github.com/Mapacheee/ExtendedHorizons/issues). Please attach your server version, plugin list, and relevant logs.
- **Discord Community:** Join our community for help and discussion at [discord.gg/yA3vD2S8Zj](https://discord.gg/yA3vD2S8Zj).
- **Donate:** If you find this project valuable, consider supporting development on [PayPal](https://paypal.me/mapachedou).
