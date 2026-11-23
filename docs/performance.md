# Performance

## Paper and ExtendedHorizons v4

Measured on **November 12, 2026**, using **Paper 26.3 build 175**, **50 simulated Java clients**, a 6 GiB maximum server heap and the same pregenerated world and flight route. Every run received **90 seconds of initial loading**, followed by **35 seconds of flight**. Paper alone uses a native view distance of **32**; EH uses a native distance of **4** and extends visibility to **32**. Simulation distance is **2** and Netty uses **4 threads** throughout. EH uses a **256 MiB / 30-second cache** and a **192 MiB/s** uncompressed send budget.

| Metric | Paper only, native 32 | EH 32, flush consolidation off | EH 32, flush consolidation on |
| :--- | :---: | :---: | :---: |
| **Runs** | 2 | 1 | 2 |
| **Terrain Coverage (end of flight)** | 94.36–95.25% | 99.70% | 99.72–99.73% |
| **Received Network Payload (flight)** | 600–639 MiB | 1,107 MiB | 1,166–1,169 MiB |
| **Mean Server CPU** | 30.8–31.5% | 26.3% | 11.7–13.4% |
| **Mean Used Java Heap (flight)** | 3,537–3,604 MiB | 923 MiB | 831–867 MiB |
| **Server Tick Duration (p95)** | 61.9–83.6 ms | 17.9 ms | 15.8–22.0 ms |
| **Local RTT (p95)** | 894.2–928.0 ms | 395.4 ms | 8.5–9.6 ms |
| **Median Time to 95% Coverage during arrival** | 48.2–49.4 s | 36.1 s | 35.9–36.0 s |
| **Median Flight Speed** | 57.9–58.0 blocks/s | 58.7 blocks/s | 60.3–60.5 blocks/s |
| **Position Corrections during flight** | 0 | 0 | 0 |

Ranges show the minimum and maximum per-run results, including per-run p95 values; they are not confidence intervals or pooled percentiles. The two EH modes use the same JAR and settings, changing only `consolidate-flushes`. None of the runs in this table used a profiler or generated new terrain.

These tests used clustered players, with the server and clients sharing one Windows machine with an AMD Ryzen 7 5800H and 16 logical processors. CPU is the server process's share of total host CPU capacity, excluding the simulated clients. Traffic counts all clientbound application bytes, excluding TCP/IP headers. RTT uses protocol ping responses over localhost. Coverage counts received chunks within the same 32-chunk target, rather than rendering performance or FPS. Mean used heap includes temporary allocations and is not retained memory or process RSS. Mob spawning, weather changes and random ticks were disabled; far players, chunk updates and EH Anti-Xray were disabled for the main comparison.

The optimization uses [Netty flush consolidation](https://netty.io/4.1/api/io/netty/handler/flush/FlushConsolidationHandler.html) for both native packets and extended chunks on active EH TCP connections. It reduces transport overhead while preserving packet order and the existing encoding path. EH sent more application data than Paper alone in this comparison. These results do not establish a universal CPU, latency or bandwidth advantage, and distant snapshots do not provide the full behavior of native chunks.

A separate 50-client run enabled far players with a limit of 64 per viewer, equipment and actions, chunk updates, and EH Anti-Xray. It measured **11.8% server CPU**, **8.2 ms RTT p95**, **98.47% final flight coverage**, and **1,597 MiB received payload**. The bots flew without editing, combat or equipment-change load; functional checks were separate. Optional features can change delivery and bandwidth costs, so this result is separate from the main comparison.

## Streaming performance

For pregenerated terrain at a 32-chunk target, start with `cache-mib: 256`, `cache-seconds: 30`, `workers: 2` and the default send limits. Existing configuration values are preserved when upgrading; change these settings explicitly and run `/eh reload` to adopt the new cache defaults. Keep your far-player, chunk-update, WorldEdit and Anti-Xray settings enabled as needed.

Set the server's native view distance below EH's target to offload distant terrain delivery; the benchmark uses 4 native chunks and a 32-chunk EH target. EH keeps native chunks under the server's control.

Leave `consolidate-flushes: true` to use the optimized transport path. Winter adds the key when it is missing during startup or `/eh reload`; an explicit `false` is preserved. EH installs its own handler while extended streaming is active on a TCP connection and removes it when that stream is disabled or detached. The existing server handlers remain in place. Setting this option to `false` restores EH's previous flush behavior without disabling its other features.

Keep `reuse-encoded-chunks: true` to avoid serializing and hashing the same immutable EH snapshot for each viewer when its compressed payload is still cached. Native chunk packets retain their normal encoding path. EH automatically falls back when the connection has an unrecognized encoder or interceptor in the affected pipeline. Set this option to `false` when comparing or troubleshooting the transport path. Winter fills the missing key on startup or `/eh reload` while preserving an explicit `false`.

The `encodedHits` counter in `/eh stats` counts uses of this path, including duplicate deliveries suppressed before encoding. These hits are included in `compressionHits`; they are not additional chunks or a coverage measurement.

For a faster initial load, test `global-mib-per-second: 256` if CPU and network capacity permit. Earlier budget-only tests, before flush consolidation, reduced median arrival time to 95% coverage from **36.3–36.7 seconds to 28.6 seconds** with 50 clients spread across the world. Recheck coverage, RTT and outbound traffic on your host when increasing this budget.

Use the counter changes in `/eh stats` over a fixed interval to guide tuning:

- For gaps after a teleport, compare two `/eh stats` readings while standing still. `missingOnDisk` counts reads with no saved chunk and `diskRejected.INCOMPLETE` counts records that have not reached full generation. These counters include repeated reads of the same coordinates. For a complete chunk with unusable saved lighting, EH asks Paper to load the existing chunk with generation disabled. Up to two such requests run at once. Successful captures increment `recoveredLight`.
- If `diskReads` and `loadedCaptures` keep increasing rapidly while players revisit the same terrain, increase `cache-mib` if heap capacity permits. Increasing the TTL helps reuse snapshots that would otherwise expire; live edits require chunk updates to be enabled.
- If source preparation is keeping up but delivery is repeatedly `throttled`, the send budget may be limiting initial loading. Increasing `global-mib-per-second` can trade more CPU and network activity for faster delivery. Both bandwidth settings count **uncompressed packet bytes**, so monitor actual outbound traffic as well.
- The `paced` delivery counter in `/eh stats` records when EH holds back terrain to limit data queued ahead of gameplay packets. Pacing uses the encoded terrain size after compression when available. `measuredTerrainBytes` and `estimatedTerrainBytes` report which sizes EH used for pacing.
- Increase `workers` only when cache misses keep source preparation busy and the machine has spare CPU. Additional workers do not increase the send budget or remove the cost of native player tracking.
- If the network event loop is backed up, increasing the send budget can make latency worse even while TPS remains healthy. Check RTT, actual movement and delivery coverage together.

Paper's [`settings.netty-threads`](https://docs.papermc.io/paper/reference/spigot-configuration/) is a separate server setting. The comparison above keeps 4 threads; the measured gains do not require increasing that count. Profile CPU and tick times separately before changing it.

Snapshot and compression cache budgets do not cap the plugin's total memory use. Temporary buffers, active reads, connection state and the server's native chunks also require memory.

[Configuration reference](configuration.md) · [Back to ExtendedHorizons](../README.md)
