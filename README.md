# Voice Bridge

A server-side [Paper](https://papermc.io/) plugin that bridges proximity voice chat
between [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat)
and [Plasmo Voice](https://modrinth.com/plugin/plasmo-voice), allowing players using different voice mods to hear each
other.

## How it works

Both Simple Voice Chat (SVC) and Plasmo Voice (PV) encode audio with Opus at 48 kHz, mono, 20 ms frames. Voice Bridge
intercepts audio packets from each mod's API and relays them to the other — no transcoding required. The result is
low-latency, cross-mod proximity voice chat with minimal CPU overhead.

```
SVC Player ──► SVC API ──► Voice Bridge ──► PV API ──► PV Player
PV Player  ──► PV API  ──► Voice Bridge ──► SVC API ──► SVC Player
```

## Requirements

- Paper 1.21.4+ (or Folia)
- Java 21+
- [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat) (server-side plugin)
- [Plasmo Voice](https://modrinth.com/plugin/plasmo-voice) (server-side plugin)

## Installation

1. Install both Simple Voice Chat and Plasmo Voice on your Paper server
2. Download `voice-bridge-<version>.jar` from [Releases](../../releases)
3. Place it in your server's `plugins/` directory
4. Restart the server

## Building from source

```shell
./gradlew build
```

The plugin jar will be in `build/libs/`.

### Running a dev server

```shell
# With Mojang mappings (for debugging)
./gradlew runDevBundleServer

# With obfuscation (production-like)
./gradlew runServer
```

## Configuration

A `config.yml` is generated on first run in `plugins/voice-bridge/`:

```yaml
bridge:
  enabled: true       # Enable/disable the bridge
  debug: false        # Enable debug logging

proximity:
  default-distance: 48.0      # Default voice range in blocks
  whisper-multiplier: 0.33    # Whisper range = distance * multiplier
  max-distance: 128.0         # Maximum allowed voice distance

audio:
  passthrough: true            # Opus passthrough (recommended)
  force-transcode: false       # Force transcoding (not yet implemented)
```

### Per-world distance overrides

You can override the default voice distance for specific worlds:

```yaml
proximity:
  world-overrides:
    world_nether: 24.0
    world_the_end: 64.0
```

## Commands

| Command                        | Description                             |
|--------------------------------|-----------------------------------------|
| `/voicebridge status`          | Show bridge status, metrics, and config |
| `/voicebridge players`         | List active bridged sessions            |
| `/voicebridge reload`          | Reload configuration                    |
| `/voicebridge debug [on\|off]` | Toggle debug logging                    |

Alias: `/vb`

Permission: `voicebridge.admin` (default: op)

## Architecture

```
┌────────────────────────────────────────────────┐
│              VoiceBridgePlugin                 │
├───────────┬───────────┬────────────┬───────────┤
│ SvcAdapter│ PvAdapter │ AudioRelay │  Session  │
│           │           │            │  Manager  │
├───────────┴───────────┴────────────┴───────────┤
│  SpatialMapper │ BridgeConfig │ BridgeMetrics  │
└────────────────────────────────────────────────┘
```

- **SvcAdapter** — integrates with Simple Voice Chat via `VoicechatPlugin`
- **PvAdapter** — integrates with Plasmo Voice via `@Addon` / `AddonInitializer`
- **AudioRelay** — central router that forwards audio frames between adapters
- **SessionManager** — tracks which mod each player is using
- **SpatialMapper** — converts between SVC float distances and PV short distances

## Known limitations

- Group/channel bridging is not supported (proximity chat only)
- Transcoding mode is not yet implemented (passthrough only)
- SVC-only players may not receive audio from PV players in some edge cases

## License

[MIT](LICENSE)
