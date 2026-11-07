<p align="center">
  <img src="docs/logo/aegis-banner.png" alt="Aegis: faster, safer networking for Minecraft Forge 1.20.1" width="100%">
</p>

# Aegis

Aegis is a networking mod for Minecraft 1.20.1 on Forge. It combines and fixes the best parts of [Krypton](https://github.com/astei/krypton), [VMP (Very Many Players)](https://github.com/RelativityMC/VMP-fabric) and [Packet Fixer](https://github.com/TonimatasDEV/PacketFixer), and adds server-side connection security that vanilla 1.20.1 does not have. It is vanilla-compatible on the wire: Aegis registers no network channels.

> **Optional on either side.** Install it on the server for the full set, and on clients for faster packet handling. A client without Aegis can join an Aegis server and the other way round. Aegis was called RatNetworking and then Axial before 0.5.0.

## Features

This is the feature set Aegis will have when its [roadmap](#roadmap) is complete. Items marked † are experimental: each is measured first, ships off by default, and is dropped and reported if it does not pay.

### Packet pipeline

- **Zero-copy frame splitter.** Skips runs of zeros from "nullping" probes and rejects oversized frames from the header alone.
- **Zero-copy length prepender,** with the header written as its own buffer.
- **Native compression.** libdeflate through velocity-native on Linux and macOS arm64, and on Windows x64 with Java 22 or newer, libdeflate 1.26 through Java's Foreign Function and Memory API. Everything else uses `java.util.zip`, and any native failure falls back to it with one log line. The compression level is configurable (default 6).
- **A lookup table for VarInt sizes,** and far less allocation: 83 to 96 % less on the frame decoder and the compression handlers.

### Flushing and the server tick

- **Flush consolidation.** One flush per connection after a level ticks its chunks and entity trackers, instead of one per packet, while still flushing every 64 KiB. A mod that writes without flushing is flushed at the next network tick, so nothing waits.
- **Non-blocking disconnect** for remote connections.
- **Entity tracker pre-check** that skips `updatePlayer` calls that can only be no-ops, with exactly vanilla's result, and a **chunk entity lookup** that looks only at the chunk being sent.
- **Broadcast memoisation†.** Compress a broadcast packet once for all of its receivers, for an allowlist of vanilla packet types. It ships off by default, and only if instrumented counts show redundant compression is a real share of network CPU.

### Limits, timeouts and fixes

- **Packet Fixer's job, done safely.** Clientbound limits of 128 MiB and serverbound 16 MiB only after login, so an unauthenticated connection cannot make the server buffer huge frames. A decompression bomb is capped by direction and by a deflate-ratio check, and decompression must produce exactly the claimed size.
- **Sensible timeouts.** 120 s read and login timeouts that apply only once a connection logs in, so a dead server in the server list still gives up after vanilla's 30 s.
- **Connection bug fixes.** Login success can no longer go out uncompressed on a busy socket ("Badly compressed packet"), a native compressor is no longer leaked when a connection closes during setup, and a zlib stream that asks for a preset dictionary no longer spins a network thread at 100 % CPU.
- **No leaked buffers.** Login queries and custom payloads are copied to heap buffers, and the native memory of replaced deflaters is freed at once.

### Server security

- **Connection throttle.** Per address, at most 10 new connections per 3 seconds and 8 connections at once that have not reached the game. Over a limit the connection closes as soon as it opens, before any decoding. Loopback and trusted addresses are never limited, and IPv6 counts per /64.
- **Offence bans.** An address that sends a malformed frame length, an oversized frame, a decompression bomb, an impossible compressed header or a preset-dictionary stream is refused for 60 seconds. No real client sends any of these.
- **A quiet log.** Refusals are logged once a minute per address and reason, so a flood cannot fill the console.
- **A block log for fail2ban and CrowdSec** (optional): one line per offence in a fixed format at `logs/aegis-security.log`, with example filter and jail files. Firewall privileges stay outside the game process.
- **Proxy aware.** With Proxy-Compatible-Forge, BungeeForge or FabricProxy-Lite installed, the throttle and the block log turn themselves off, because every player shares the proxy's address.

### Compatibility

- **Overlapping modules switch off by themselves** next to Packet Fixer, Connectivity, VMP and Immersive Portals, and `/aegis` (op) lists the active modules, natives, limits and any notes.
- **Camera mods work:** SecurityCraft cameras, Ars Nouveau scrying and Steam 'n' Rails conductor possession are handled by the entity tracker pre-check.
- **Tested with** All the Mods 9, Better MC 4 and Valhelsia 6 server packs and the RatPack 2.9 server, on Java 17 and Java 25.

## Performance

Aegis is built on one rule: **a faster path ships only after a benchmark or an in-game test shows it is faster.** Several ideas were measured or evaluated and not adopted, and the changelog records why.

<p align="center">
  <img src="docs/img/aegis-cost.png" alt="Measured cost of Aegis's packet handlers against vanilla's" width="90%">
</p>

The figures below are the project's own PipelineBench microbenchmarks: 20,000 packets of a mixed small and chunk-sized workload, against vanilla's handlers, on Windows with Temurin 25 unless noted. They measure the handlers, not a server tick.

| Metric | Result |
|---|---|
| **Frame rate** | No claim. Networking is not on the render thread, and the client-side gain is in handler cost and allocation |
| **Compression** | Encoder 4.96x and decoder 2.09x faster than vanilla with libdeflate (Windows, Java 25). Linux with velocity-native: 4.4x and 1.9x. Output is 0.4 % smaller than Java's, so speed is not bought with a worse ratio |
| **Packet framing** | Frame decoder 4.2x faster than vanilla on Java 25 (1.7x on Java 17), length prepender 1.8 to 2x on Windows Java 17, VarInt size about 10x |
| **Sync** | One flush per connection per tick instead of per packet, at the cost of one volatile read per connection per tick |
| **Server tick** | No gain claimed. In All the Mods 9 the one-minute median was 14.7 ms as shipped and 14.9 ms with Aegis replacing Packet Fixer, within noise. The 95th percentile read 32.1 and 38.0 ms in single one-minute runs per setup |
| **Overhead** | `VarInts.write` is about 1 ns slower than vanilla's per packet, and it is used only for Aegis's own length prefix |
| **Security** | A STATUS connection announcing a 200 MiB frame stayed open for 15 s or more with Packet Fixer and closes in about 3 ms with Aegis |

## Roadmap

| Release | Theme | Content |
|---|---|---|
| 0.1.x | Pipeline | Krypton, VMP and Packet Fixer merged and fixed, compatibility fixes for the big packs |
| 0.2.x | Correctness | Login compression order, compressor leak, ping timeout, flush safety net, chunk entity lookup guard |
| 0.3.0 | Security | Connection throttle, offence bans, block log and fail2ban files |
| 0.4.0 | Java 25 and Windows | The Java 25 audit, libdeflate on Windows through FFM |
| 0.5.x | Aegis (**current: 0.5.3**) | The rename from Axial, and the new logo |
| Next | Experimental | Broadcast memoisation†, only if instrumentation justifies it |

Dates are not promised. Each release has a measure-first gate, and an item that fails its gate is dropped and recorded under "Not adopted" in [`changelog.txt`](changelog.txt). Not planned: an io_uring transport (research only), TCP_QUICKACK, replacing velocity-native's JNI, and swapping Minecraft's shared Netty.

## Requirements

- Minecraft **1.20.1** with Forge **47.x**
- Java **17** or newer. Java 22 or newer adds native compression on Windows, and Java 24 and newer print a harmless native-access warning (`--illegal-native-access=allow` removes it)
- Not compatible with Krypton forks that bundle velocity-native (Krypton Reforged, Krypton Reno), or with the old Axial and RatNetworking jars. Forge stops at loading with a message that names them

## Installation

1. Remove Krypton Reforged or Krypton Reno, Axial or RatNetworking, and Packet Fixer if you have them (Packet Fixer can stay, but Aegis then turns its overlapping modules off).
2. Place `aegis-1.20.1-<version>.jar` in `mods` on the server and on clients.
3. Options are in `config/aegis.properties`, copied from `axial.properties` or `ratnetworking.properties` on the first start if one exists. Every module can be turned off.

## Building

```bash
./gradlew build
```

It needs a JDK 17 and writes `build/libs/aegis-1.20.1-<version>.jar`. `./gradlew test` runs the unit tests with Netty's leak detector at paranoid level.

## Licence and attribution

- Aegis is licensed under the **GNU General Public License v3.0 or later** ([`LICENSE`](LICENSE)). Copyright (C) 2026 SolarBiscuit.
- It contains code derived from, or bundles, the works below. Their licence texts are in [`licenses/`](licenses/) and in the jar, and [`NOTICE`](NOTICE) says what is used from each:
  - **Krypton** by Andrew Steinborn and contributors, LGPL-3.0.
  - **VMP (Very Many Players)** by ishland, MIT.
  - **Packet Fixer** by TonimatasDEV, MIT.
  - **velocity-native 3.4.0** by the Velocity contributors, GPL-3.0, bundled unmodified as a jar-in-jar. It contains libdeflate and links against the system's OpenSSL.
  - **libdeflate 1.26** by Eric Biggers and Google LLC, MIT, bundled unmodified as the official Windows x86_64 build.
- Aegis is an independent project. It is not affiliated with, endorsed by, or supported by Mojang Studios, Microsoft, Minecraft Forge, or the authors of the mods above.

## Reporting issues

Issues belong in the [Aegis issue tracker](https://github.com/SolarBiscuit/AegisNetworking/issues). Please include the Aegis version, your Java version, the other networking mods you run, and `latest.log`. The output of `/aegis` helps.
