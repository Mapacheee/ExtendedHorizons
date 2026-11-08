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

Clients receive lightweight snapshots that dramatically expand visible render distance while keeping server tick times, memory usage, and entity tick loops completely unburdened.

> - **Shared chunk reads & compression cache:** Compressed packet data is cached and shared across players viewing identical terrain.
> - **Direction-aware delivery:** Prioritizes chunks along the player's vector of travel, smoothly filling surrounding terrain when stationary.
> - **Native Anti-Xray integration:** Custom block masking in distant chunks to protect ores without leaking underground data.
> - **Far-player tracking:** Optional distant player rendering and synchronization beyond vanilla tracking ranges.

---

##  Requirements

- **Java 25**
- **Paper** (Tested across 1.21.11, 26.1.2, 26.2, and 26.3)
- **[MapacheeeLib](https://github.com/Mapacheee/MapacheeeLib/releases)** (Required dependency, install alongside EH)
- **[WorldEdit](https://enginehub.org/worldedit)** or **[FastAsyncWorldEdit (FAWE)](https://github.com/IntellectualSites/FastAsyncWorldEdit/releases)** *(Optional, for real-time terrain invalidation on bulk edits)*

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

## ⚙️ Configuration Reference

A snippet of key settings in `config.yml`:

| Setting | Default | Description |
| :--- | :---: | :--- |
| `view-distance` | `32` | Target view radius in chunks (includes native server distance). |
| `disabled-worlds` | `[]` | List of world names where extended chunks are disabled. |
| `player-mib-per-second` | `8` | Maximum uncompressed chunk packet bandwidth per player per second. |
| `global-mib-per-second` | `192` | Global server-wide bandwidth budget for chunk streaming. |
| `workers` | `2` | Background worker threads for chunk reading and processing. |
| `far-players.enabled` | `false` | Renders distant players in fake chunks (with equipment and action support). |
| `chunk-updates.enabled` | `false` | Dynamically updates fake chunks on block break, place, growth, and physics. |
| `chunk-updates.worldedit`| `false` | Listens to WorldEdit / FAWE commits to update distant terrain after large edits. |
| `anti-xray.enabled` | `false` | Obfuscates specified ores/blocks in extended chunks. |
| `anti-xray.hidden-blocks`| *Ore list* | List of block IDs to mask out in fake chunk packets. |

> [!TIP]
> **Anti-Xray Notice:** If Paper's engine Anti-Xray is active for a world, ExtendedHorizons requires its own `anti-xray.enabled: true` with a configured block list to send extended chunks in that world.

---

## Commands & Permissions

All commands require the `extendedhorizons.admin` permission (granted to OPs by default).

| Command | Description |
| :--- | :--- |
| `/eh` or `/eh stats` | Shows session count, delivery performance, chunk source stats, and far players status. |
| `/eh farplayers <player>` | Inspects tracking details for a distant player from your current connection (in-game only). |
| `/eh reload` | Reloads `config.yml` on the fly. |

---

## 🛠️ Building from Source

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

## 📊 Metrics

ExtendedHorizons collects anonymous metrics via FastStats (integrated through MapacheeeLib).  
You can view global stats at the **[FastStats Dashboard](https://faststats.dev/project/extendedhorizons/minecraft-plugin)**.

---

## 🤝 Contributing & Support

- **Bug Reports & Feature Requests:** Open an issue on our [GitHub Issues](https://github.com/Mapacheee/ExtendedHorizons/issues). Please attach your server version, plugin list, and relevant logs.
- **Discord Community:** Join our community for help and discussion at [discord.gg/yA3vD2S8Zj](https://discord.gg/yA3vD2S8Zj).
- **Donate:** If you find this project valuable, consider supporting development on [PayPal](https://paypal.me/mapachedou).
