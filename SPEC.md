# Voice Bridge

Server-side Paper plugin that bridges proximity voice chat between
[Simple Voice Chat](https://github.com/henkelmax/simple-voice-chat) (SVC) and
[Plasmo Voice](https://github.com/plasmo-voice/plasmo-voice) (PV),
allowing players using either mod to hear each other seamlessly.

## Table of Contents

1. [Protocol Comparison](#1-protocol-comparison)
2. [Architecture](#2-architecture)
3. [Audio Pipeline](#3-audio-pipeline)
4. [Protocol Integration](#4-protocol-integration)
5. [Spatial Mapping](#5-spatial-mapping)
6. [Permissions and Channels](#6-permissions-and-channels)
7. [Threading Model](#7-threading-model)
8. [Failure Modes and Recovery](#8-failure-modes-and-recovery)
9. [Security](#9-security)
10. [Observability](#10-observability)
11. [Configuration](#11-configuration)
12. [Build and Packaging](#12-build-and-packaging)

---

## 1. Protocol Comparison

All values verified by source code inspection of both repositories.

| Property | Simple Voice Chat | Plasmo Voice |
|---|---|---|
| Codec | Opus (native via opus4j, Java fallback via Concentus) | Opus (via CodecManager, OpusDecoderInfo) |
| Sample rate | 48 000 Hz | 48 000 Hz (default, configurable) |
| Frame size | 960 samples / 20 ms | 960 samples / 20 ms (default, configurable) |
| Channels | Mono | Mono (default), Stereo (per-source opt-in) |
| UDP encryption | AES-128-GCM (12-byte IV, 128-bit tag) | AES-128-CBC (PKCS5Padding, 16-byte IV) |
| Auth handshake | Plugin channel, 16-byte AES secret | Plugin channel, RSA key exchange, AES key |
| Server API | `VoicechatPlugin` + `BukkitVoicechatService` | `@Addon` + `AddonInitializer` + `AddonsLoader` |
| DI | `VoicechatApi` passed to `initialize()` | `@InjectPlasmoVoice` field injection |
| Audio interception | `MicrophonePacketEvent` | `ServerActivation.onPlayerActivation` |
| Audio injection | `EntityAudioChannel.send(byte[])` | `ServerPlayerSource.sendAudioFrame(byte[], long, short)` |
| Audio end signal | `EntityAudioChannel.flush()` | `ServerPlayerSource.sendAudioEnd(long, short)` |
| Distance type | `float` (blocks) | `short` (blocks) |
| Sequence numbers | Not exposed via API | `BaseAudioPacket.sequenceNumber` |
| Player detection | `PlayerConnectedEvent` | `UdpClientConnectEvent` |

Both mods default to identical Opus parameters. Encoded frames can pass between
them without transcoding.

---

## 2. Architecture

### 2.1 Component Diagram

```
                     Minecraft Paper Server
  ┌──────────────────────────────────────────────────────────┐
  │                                                          │
  │  ┌───────────────┐                  ┌───────────────┐    │
  │  │  SVC Server   │                  │  PV Server    │    │
  │  │  UDP :24454   │                  │  UDP :port    │    │
  │  │  AES-GCM      │                  │  AES-CBC      │    │
  │  └───────┬───────┘                  └───────┬───────┘    │
  │          │ VoicechatPlugin API              │ Addon API  │
  │  ┌───────┴──────────────────────────────────┴───────┐    │
  │  │                 Voice Bridge                     │    │
  │  │                                                  │    │
  │  │  ┌────────────────┐         ┌────────────────┐   │    │
  │  │  │  SvcAdapter    │         │  PvAdapter     │   │    │
  │  │  │  ┌───────────┐ │         │ ┌────────────┐ │   │    │
  │  │  │  │ Mic event │ │ ───────▶│ │ Player     │ │   │    │
  │  │  │  │ listener  │ │         │ │ source     │ │   │    │
  │  │  │  └───────────┘ │         │ └────────────┘ │   │    │
  │  │  │  ┌───────────┐ │         │ ┌────────────┐ │   │    │
  │  │  │  │ Entity    │ │◀─────── │ │ Activation │ │   │    │
  │  │  │  │ channel   │ │         │ │ listener   │ │   │    │
  │  │  │  └───────────┘ │         │ └────────────┘ │   │    │
  │  │  └────────────────┘         └────────────────┘   │    │
  │  │                                                  │    │
  │  │  ┌──────────┐ ┌─────────┐ ┌────────┐ ┌────────┐  │    │
  │  │  │ Audio    │ │ Session │ │ Spatial│ │ Config │  │    │
  │  │  │ Relay    │ │ Manager │ │ Mapper │ │ Loader │  │    │
  │  │  └──────────┘ └─────────┘ └────────┘ └────────┘  │    │
  │  └──────────────────────────────────────────────────┘    │
  │                                                          │
  │  SVC Clients ◄── UDP ──▶ Server ◄── UDP ──▶ PV Clients   │
  └──────────────────────────────────────────────────────────┘
```

### 2.2 Components

| Component | Class | Responsibility |
|---|---|---|
| SVC Adapter | `SvcAdapter` | Implements `VoicechatPlugin`. Intercepts SVC audio via `MicrophonePacketEvent`. Creates `EntityAudioChannel` per PV speaker for outbound relay. |
| PV Adapter | `PvAdapter` | Implements `AddonInitializer` with `@Addon`. Intercepts PV audio via `ServerActivation.onPlayerActivation`. Creates `ServerPlayerSource` per SVC speaker for outbound relay. |
| Audio Relay | `AudioRelay` | Central router. Receives Opus frames from one adapter, maps distance, forwards to the other. |
| Session Manager | `SessionManager` | Tracks per-player voice state in `ConcurrentHashMap<UUID, BridgeSession>`: mod type, activity timestamp. Periodic cleanup of stale sessions. |
| Spatial Mapper | `SpatialMapper` | Converts `float` (SVC) to `short` (PV) distances. Applies whisper multiplier. Supports per-world overrides. |
| Config | `BridgeConfig` | Loads YAML config. Provides distance, whisper multiplier, passthrough toggle, per-world overrides. |
| Commands | `VoiceBridgeCommand` | Brigadier command tree for `/voicebridge` (alias `/vb`). Registered via `LifecycleEvents.COMMANDS`. |
| Metrics | `BridgeMetrics` | Atomic counters: active sessions, frames relayed per direction, dropped frames, transcoding count. |

### 2.3 Data Flow

**SVC player speaks, PV player hears:**

```
SVC Client ─[UDP/AES-GCM]─▶ SVC Server
  ▶ MicrophonePacketEvent fires
  ▶ SvcAdapter.onMicrophonePacket()
    ▶ packet.opusEncodedData → byte[]
    ▶ AudioRelay.relaySvcToPv(uuid, player, opusData, seq, distance, whispering)
      ▶ SpatialMapper.svcToPvDistance(distance) → Short
      ▶ PvAdapter.sendAudioFromExternalPlayer(uuid, player, opusData, seq, pvDistance)
        ▶ ServerPlayerSource.sendAudioFrame(opusData, seq, pvDistance)
  ▶ PV Server ─[UDP/AES-CBC]─▶ PV Client
```

**PV player speaks, SVC player hears:**

```
PV Client ─[UDP/AES-CBC]─▶ PV Server
  ▶ ServerActivation.onPlayerActivation fires
  ▶ PvAdapter.onPvPlayerAudio()
    ▶ packet.data → byte[]
    ▶ AudioRelay.relayPvToSvc(uuid, player, opusData, seq, distance)
      ▶ SpatialMapper.pvToSvcDistance(distance) → Float
      ▶ SvcAdapter.sendAudioFromExternalPlayer(uuid, player, opusData, seq, svcDistance)
        ▶ EntityAudioChannel.send(opusData)
  ▶ SVC Server ─[UDP/AES-GCM]─▶ SVC Client
```

---

## 3. Audio Pipeline

### 3.1 Opus Passthrough (Default)

Both mods use identical Opus parameters by default (48 kHz, mono, 20 ms / 960 samples).
Encoded Opus frames pass through without decoding or re-encoding.

```
SVC packet.opusEncodedData ──▶ PV source.sendAudioFrame(bytes, seq, dist)
PV  packet.data             ──▶ SVC channel.send(bytes)
```

This is the lowest-latency, lowest-CPU path.

### 3.2 Transcoding (Fallback)

Transcoding is required only when codec parameters differ:

- PV configured for stereo while SVC is mono (downmix required)
- Non-default sample rate in PV config (resample required)

```
Opus bytes ──▶ Decoder.decode() ──▶ short[] PCM
  ──▶ [downmix / resample] ──▶ Encoder.encode() ──▶ Opus bytes
```

Both mods expose codec APIs:
- SVC: `VoicechatApi.createEncoder()` / `createDecoder()`
- PV: `PlasmoVoiceServer.createOpusEncoder(stereo)` / `createOpusDecoder(stereo)`

### 3.3 Timing

- Frames arrive at 50 fps (20 ms intervals)
- No jitter buffer on the bridge; frames are forwarded immediately
- Sequence numbers are maintained per outbound channel/source
- SVC does not expose sequence numbers via its API; the bridge generates incrementing values

---

## 4. Protocol Integration

### 4.1 Simple Voice Chat

**Registration.** The bridge implements `VoicechatPlugin` and registers via `BukkitVoicechatService`:

```kotlin
class SvcAdapter(private val plugin: VoiceBridgePlugin) : VoicechatPlugin {
    override fun getPluginId() = "voice-bridge"

    override fun initialize(api: VoicechatApi) {
        serverApi = api as? VoicechatServerApi
    }

    override fun registerEvents(registration: EventRegistration) {
        registration.registerEvent(MicrophonePacketEvent::class.java, ::onMicrophonePacket)
        registration.registerEvent(PlayerConnectedEvent::class.java, ::onPlayerConnected)
        registration.registerEvent(PlayerDisconnectedEvent::class.java, ::onPlayerDisconnected)
    }
}
```

**Inbound** (SVC player audio): `MicrophonePacketEvent` provides `packet.opusEncodedData` and `packet.isWhispering`.

**Outbound** (relay to SVC clients): `EntityAudioChannel` created via `api.createEntityAudioChannel(channelId, entity)`.
The channel supports `setDistance(float)`, `setFilter(Predicate)` (to target only SVC players), and `send(byte[])`.
End-of-stream is signalled by `flush()`.

**Key limitation.** `AudioSender.canSend()` returns false for players with SVC installed.
The bridge uses `EntityAudioChannel` exclusively, which works for all players.

**Player name.** SVC's `Player` interface exposes only `uuid` and `getPlayer()` (platform object).
The name is obtained by casting: `connection.player.player as? org.bukkit.entity.Player`.

### 4.2 Plasmo Voice

**Registration.** The bridge is annotated with `@Addon` and implements `AddonInitializer`.
It registers itself manually and receives dependency injection:

```kotlin
@Addon(id = "voice-bridge", name = "Voice Bridge", version = "0.1.0", authors = ["VoiceBridge"])
class PvAdapter(private val plugin: VoiceBridgePlugin) : AddonInitializer {
    @InjectPlasmoVoice
    lateinit var voiceServer: PlasmoVoiceServer

    init {
        PlasmoVoiceServer.getAddonsLoader().load(this)
    }

    override fun onAddonInitialize() {
        voiceServer.eventBus.register(this, this)
        setupProximityActivationListener()
    }

    override fun onAddonShutdown() { shutdown() }
}
```

**Inbound** (PV player audio): The proximity `ServerActivation` is obtained via
`voiceServer.activationManager.getActivationByName("proximity")`.
Audio is intercepted with `activation.onPlayerActivation { player, packet -> }`,
returning `ServerActivation.Result.IGNORED` so PV continues normal processing.
End-of-stream is intercepted with `activation.onPlayerActivationEnd`.

**Outbound** (relay to PV clients): `ServerPlayerSource` created via `sourceLine.createPlayerSource(pvPlayer, false)`.
Audio is sent with `source.sendAudioFrame(opusData, sequenceNumber, distance)`.
End-of-stream is signalled by `source.sendAudioEnd(sequenceNumber, distance)`.

**Source line.** Obtained via `voiceServer.sourceLineManager.getLineByName("proximity")` (returns `Optional`).

**Player/session events.** `UdpClientConnectEvent` and `UdpClientDisconnectedEvent` are handled via
`@EventSubscribe`-annotated methods registered on `voiceServer.eventBus`.

---

## 5. Spatial Mapping

### 5.1 Distance Conversion

SVC uses `float`, PV uses `short`. Both represent distance in blocks.

```
SVC → PV:  pvDistance = svcDistance.toInt().coerceIn(1, Short.MAX_VALUE).toShort()
PV → SVC:  svcDistance = pvDistance.toFloat()
```

Distances are clamped to a configurable maximum (default: 128 blocks).
Per-world overrides are supported.

### 5.2 Proximity Filtering

- **SVC outbound**: `EntityAudioChannel.setFilter()` restricts delivery to SVC-only players.
  PV handles its own proximity filtering internally via `ServerProximitySource`.
- **PV outbound**: `ServerPlayerSource` handles proximity filtering internally.
  SVC's `EntityAudioChannel` is positioned at the sender entity's location.

### 5.3 Whispering

SVC exposes `MicrophonePacket.isWhispering()`. PV supports variable distance per packet but has no explicit whisper concept.

When an SVC player whispers, the bridge reduces the PV distance:
`whisperDistance = normalDistance * whisperMultiplier` (default: 0.33).

### 5.4 Activation Modes

Both mods support push-to-talk and voice activation on the client. The server receives the same events regardless of activation mode. No special mapping is needed.

---

## 6. Permissions and Channels

### 6.1 Proximity (v1 Scope)

All players in the same world within the configured distance can hear each other regardless of which voice mod they use.

### 6.2 Groups (Deferred to v2)

SVC has `Group` / `VoicechatConnection.setGroup()`.
PV uses activations and source lines (different paradigm).
Group bridging requires significant design work and is out of scope for v1.

### 6.3 Mute / Deafen

- SVC: `VoicechatConnection.isDisabled()` prevents audio from being sent
- PV: `VoicePlayer.isVoiceDisabled()` / `VoicePlayer.isMicrophoneMuted()` / `MuteManager`
- Both mods suppress events for muted/deafened players, so the bridge respects these states automatically

---

## 7. Threading Model

```
Main Server Thread
  └─ Tick events, session bookkeeping

SVC Processing Thread (managed by SVC)
  └─ MicrophonePacketEvent
      └─ SvcAdapter.onMicrophonePacket() → relay to PV (non-blocking)

PV Processing Thread (managed by PV)
  └─ ServerActivation callbacks
      └─ PvAdapter.onPvPlayerAudio() → relay to SVC (non-blocking)

VoiceBridge-Cleanup (daemon, ScheduledExecutorService)
  └─ SessionManager.cleanup() every 5 seconds
```

**Rules:**
- Never block the main server thread or the voice mod threads
- `EntityAudioChannel.send()` and `ServerPlayerSource.sendAudioFrame()` are non-blocking
- All shared state is in `ConcurrentHashMap` instances
- Outbound channels/sources are created lazily via `computeIfAbsent`

---

## 8. Failure Modes and Recovery

| Failure | Detection | Recovery |
|---|---|---|
| Player disconnects | `PlayerDisconnectedEvent` / `UdpClientDisconnectedEvent` | Remove session, close channels/sources, clear sequence numbers |
| Voice mod not loaded | `Class.forName()` throws `ClassNotFoundException` | Log warning, skip adapter initialization, bridge becomes no-op |
| UDP port conflict | Both mods fail to bind | User configures different ports; bridge logs error |
| Opus decode failure | Exception during transcoding | Drop frame, increment `droppedFrames`, log at debug level |
| Sequence gap | Missing sequence numbers | Forward available frames; client-side PLC handles gaps |
| Dimension change | Player changes world | Existing channels/sources remain valid (entity-bound) |
| Stale session | `lastActivityAt` exceeds threshold | Cleanup task removes inactive sessions every 5 seconds |

---

## 9. Security

### 9.1 Encryption Boundary

The bridge operates **above** the encryption layer. Both mods handle their own UDP encryption independently:
- SVC: AES-128-GCM with per-player secrets distributed via Minecraft plugin channels
- PV: AES-128-CBC with per-player keys exchanged via RSA handshake

The bridge uses public server-side APIs that provide already-decrypted audio data. When sending audio to the target mod, the mod's server re-encrypts for the recipient client.

### 9.2 Authentication

Both mods authenticate players via Minecraft plugin channels before establishing UDP connections. The bridge only processes audio from authenticated, connected players.

### 9.3 Key Isolation

The bridge never accesses, stores, or transmits encryption keys. All audio routing uses high-level API methods.

---

## 10. Observability

### 10.1 Metrics

```kotlin
object BridgeMetrics {
    val activeSessions: AtomicInteger   // currently connected bridge sessions
    val svcToPlasmoFrames: AtomicLong   // total frames relayed SVC → PV
    val plasmoToSvcFrames: AtomicLong   // total frames relayed PV → SVC
    val droppedFrames: AtomicLong       // frames dropped due to errors
    val transcodingCount: AtomicLong    // frames that required transcoding
}
```

### 10.2 Admin Commands

Registered via Brigadier (`LifecycleEvents.COMMANDS`). Alias: `/vb`.

| Command | Description |
|---|---|
| `/voicebridge status` | Active sessions, frame counters, config state |
| `/voicebridge players` | List connected players with their voice mod |
| `/voicebridge reload` | Reload `config.yml` at runtime |
| `/voicebridge debug [on\|off]` | Toggle debug logging (runtime only) |

Permission: `voicebridge.admin` (default: op).

### 10.3 Logging

Debug mode (`bridge.debug: true`) enables per-frame logging with sender, sequence number, distance, and direction.

---

## 11. Configuration

```yaml
# plugins/voice-bridge/config.yml

bridge:
  enabled: true
  debug: false

proximity:
  default-distance: 48.0      # blocks
  whisper-multiplier: 0.33     # whisper = distance * multiplier
  max-distance: 128.0          # hard cap

audio:
  passthrough: true            # forward Opus frames without re-encoding
  force-transcode: false       # decode + re-encode every frame (high CPU)

# Per-world distance overrides
# worlds:
#   world_nether:
#     default-distance: 24.0
#   world_the_end:
#     default-distance: 64.0
```

The config file is created with defaults on first run. `BridgeConfig.load()` parses it via Paper's `YamlConfiguration`.

---

## 12. Build and Packaging

### 12.1 Platform

**Paper** server plugin for Minecraft **1.21.4**.

Both SVC and PV ship Paper-compatible server-side APIs.
The bridge ships as a standard `paper-plugin.yml` plugin with soft dependencies on both voice mods.

### 12.2 Dependencies

| Artifact | Scope | Source |
|---|---|---|
| `io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT` | Dev bundle | `paperweight-userdev` |
| `de.maxhenkel.voicechat:voicechat-api:2.6.0` | `compileOnly` | `maven.maxhenkel.de` |
| `su.plo.voice.api:server:2.1.8` | `compileOnly` | `repo.plasmoverse.com` |
| `su.plo.voice.api:common:2.1.8` | `compileOnly` | `repo.plasmoverse.com` |
| `su.plo.voice.api:server-proxy-common:2.1.8` | `compileOnly` | `repo.plasmoverse.com` |
| `su.plo.voice:protocol:2.1.8` | `compileOnly` | `repo.plasmoverse.com` |
| `su.plo.slib:api-server:1.2.0` | `compileOnly` | `repo.plasmoverse.com` |
| `org.jetbrains.kotlin:kotlin-stdlib` | `implementation` | Maven Central |

### 12.3 Build

```
./gradlew build
```

Produces three JARs in `build/libs/`:
- `voice-bridge-<version>.jar` — base (without bundled dependencies)
- `voice-bridge-<version>-all.jar` — shadow JAR (bundles Kotlin stdlib)
- `voice-bridge-<version>-reobf.jar` — remapped for production Paper

### 12.4 Project Structure

```
voice-bridge/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── SPEC.md
└── src/main/
    ├── kotlin/io/pfaumc/voicebridge/
    │   ├── VoiceBridgePlugin.kt        # Plugin entry point, lifecycle
    │   ├── BridgeMetrics.kt            # Atomic counters
    │   ├── adapter/
    │   │   ├── SvcAdapter.kt           # SVC VoicechatPlugin implementation
    │   │   └── PvAdapter.kt            # PV @Addon / AddonInitializer
    │   ├── relay/
    │   │   └── AudioRelay.kt           # Central audio router
    │   ├── session/
    │   │   └── SessionManager.kt       # Per-player session tracking
    │   ├── spatial/
    │   │   └── SpatialMapper.kt        # Distance conversion
    │   ├── config/
    │   │   └── BridgeConfig.kt         # YAML config loader
    │   └── command/
    │       └── VoiceBridgeCommand.kt   # Brigadier command tree
    └── resources/
        └── paper-plugin.yml            # Plugin descriptor
```
