# Configuration

ExtendedHorizons loads these settings from `plugins/ExtendedHorizons/config.yml`. Apply changes with `/eh reload`.

| Setting | Default | Description |
| :--- | :---: | :--- |
| `view-distance` | `32` | Target view radius in chunks (includes native server distance). |
| `disabled-worlds` | `[]` | List of world names where extended chunks are disabled. |
| `distance-permissions.enabled` | `true` | Assigns target distances through `extendedhorizons.max.<distance>` permissions. |
| `distance-permissions.default-distance` | `-1` | Uses `view-distance` for players without a matching permission. An explicit value from 2 to 128 sets a separate fallback. |
| `distance-permissions.refresh-seconds` | `5` | Permission refresh interval when LuckPerms event integration is unavailable. |
| `player-mib-per-second` | `8` | Maximum uncompressed chunk packet bandwidth per player per second. EH paces each connection according to client response times. |
| `global-mib-per-second` | `192` | Global server-wide bandwidth budget for chunk streaming. |
| `consolidate-flushes` | `true` | Batches TCP flushes on active EH connections to reduce transport overhead. |
| `reuse-encoded-chunks` | `true` | Reuses cached wire payloads for unchanged EH snapshots on supported native TCP pipelines. |
| `workers` | `2` | Background worker threads for chunk reading and processing. |
| `cache-mib` | `256` | Snapshot cache budget in MiB. The compressed packet cache has an additional budget of one quarter of this value. |
| `cache-seconds` | `30` | Maximum age of a cached snapshot. Enabled chunk updates invalidate edited snapshots before this expires. |
| `far-players.enabled` | `true` | Shows distant players with optional equipment and actions. |
| `chunk-updates.enabled` | `true` | Refreshes distant terrain after supported block, bucket, and sign edits. |
| `chunk-updates.worldedit`| `false` | Listens to WorldEdit / FAWE commits to update distant terrain after large edits. |
| `anti-xray.enabled` | `false` | Obfuscates specified ores/blocks in extended chunks. |
| `anti-xray.hidden-blocks`| *Ore list* | Block IDs to mask in extended chunk packets. |

For bulk edit updates, install WorldEdit or FastAsyncWorldEdit and enable `chunk-updates.worldedit`. [WorldEdit](https://enginehub.org/worldedit) · [FastAsyncWorldEdit](https://github.com/IntellectualSites/FastAsyncWorldEdit/releases)

> [!TIP]
> **Anti-Xray Notice:** If Paper's engine Anti-Xray is active for a world, ExtendedHorizons requires its own `anti-xray.enabled: true` with a configured block list to send extended chunks in that world.

### Distance permissions

Set `distance-permissions.enabled: true` to assign view distances through permissions. Players without a matching node use `distance-permissions.default-distance`. Its default, `-1`, follows the current `view-distance`, including changes applied with `/eh reload`; an explicit value from 2 through 128 overrides that fallback. Existing numeric values are preserved when upgrading, so change an existing `8` to `-1` to adopt inheritance. The highest granted `extendedhorizons.max.<distance>` node wins, from 2 through 128. For example, `extendedhorizons.max.16` grants a total target radius of 16 chunks, including native chunks. `extendedhorizons.bypass` uses the configured `view-distance` maximum. These nodes default to false, including for operators.

The configured maximum, client render distance, disabled worlds and Anti-Xray requirements still apply. Permissions control extended delivery and cannot reduce chunks sent by the server's native view distance. Setting the fallback or a permission to 2 disables additional delivery when the native distance is at least 2. The V3 node `extendedhorizons.max.1` is accepted as an alias for 2.

With LuckPerms installed, EH refreshes on user-data recalculation, group changes and context-update events, coalescing notifications on the player's scheduler. It also refreshes on join, teleport, world changes and `/eh reload`. Without the event integration, `distance-permissions.refresh-seconds` controls periodic refresh (5 seconds by default). LuckPerms context events are best-effort hints; custom context calculators must signal updates correctly. Direct permission attachment changes outside LuckPerms may require `/eh reload` while the event integration is active. `/eh stats` reports which mode is in use. LuckPerms is optional and is not bundled.

[Performance benchmarks and tuning](performance.md) · [Back to ExtendedHorizons](../README.md)
