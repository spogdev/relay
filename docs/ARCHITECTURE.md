# Architecture

Relay is a **client-only** Fabric mod: trusted players share live coordinates and armor durability
on a HUD, with mutual-trust gating, per-server and global trust lists, per-player visibility
toggles, a block list, and an "I'm being attacked" alert keybind that flashes the sender red on
teammates' HUDs.

Nothing travels through the Minecraft server. Coordinates and alerts go through a standalone
**relay service** the user hosts themselves, so the mod works on any server without server-side
support — including servers that strip the packets a client-side mod would otherwise read.

## Layout

| Path | What |
|---|---|
| `src/main/java/dev/spog/teamlocator/` | the mod (client-only; `environment: "client"`, no `main` entrypoint) |
| `relay/` | the relay service — a separate Gradle subproject, plain Java, no Minecraft dependency |
| `docs/SETUP.md` | hosting the relay: VPS, systemd, Caddy/TLS, troubleshooting |

Mod id `relay`, base package `dev.spog.teamlocator`. Single source set; client classes are guarded
with `@Environment(EnvType.CLIENT)`. Client config (trust lists, toggles, HUD position, relay URL)
lives in `config/relay.json`.

## Transport

`client/net/RelayClient.java` speaks JSON over a WebSocket, using the JDK's built-in
`java.net.http.WebSocket` so the mod ships zero dependencies of its own. One JSON object per frame,
discriminated by a `type` field; `relay/.../protocol/Messages.java` defines every shape.

`PROTOCOL_VERSION` is checked on connect and a mismatch is **rejected outright**, so the relay and
both client builds must be released together. Keep each payload's codec beside its Java record, in
one file, so the two cannot drift apart.

## Identity

Players are identified by the Mojang session-server handshake — the same one vanilla servers use.
The relay issues a nonce, the client calls `joinServer(profileId, accessToken, nonce)`, and the
relay confirms it with `hasJoinedServer`.

**The relay uses the UUID Mojang returns, never one the client claims.** That is the whole basis of
identity here: a client cannot assert who it is. No access token is ever stored or sent to the
relay — the client passes it directly to Mojang.

## Scope: who shares a server with whom

Positions never cross Minecraft servers. Each connection carries a **scope**, and the relay only
routes within one.

The scope is the **resolved `ip:port` of the client's live connection**
(`TeamConfig.currentServerKey()`), not the address the player typed. Two players on one server must
produce the same scope or they are silently invisible to each other, and the typed address does not
guarantee that: one may join by hostname and another by IP, both correct. The resolved socket is
identical for everyone on the server whatever route they took, so it is the only honest identity
available client-side.

The port is part of the key — shared hosts put unrelated servers on one box, distinguished only by
port — and the relay's `normalizeScope` does case/whitespace only, since the client's key is
already canonical.

## Trust gating

**Enforced on the relay** (`relay/.../RelayRouter.java`), never client-side — a client cannot opt
itself into someone else's feed. Trust is one-directional: you seeing someone does not mean they
see you. Alerts additionally require *mutual* trust.

Armor rides the position-sharing set, so it can never reach anyone who cannot already see you.

`relay/src/test` covers this over real WebSockets against the real server: no leak to untrusted
viewers, mutual-trust alerts, block filtering, cross-server scoping, unauthenticated-socket
rejection, armor gating, and scope keying. Run `./gradlew :relay:test` after touching routing or
scope — a bug in either is a privacy leak, not a cosmetic fault.

## Optional integrations

Xaero's Minimap and World Map are `compileOnly`: every `xaero.*` reference sits behind an
`isModLoaded` guard, and the mixins are `@Pseudo` so they are skipped when the mods are absent.

`@Pseudo` fails **silently** — a mixin whose target moved compiles, loads, and simply never applies,
leaving icons quietly missing. Verify descriptors against the real Xaero jar (`javap`) rather than
assuming. Note that Xaero ships **intermediary**-compiled jars, so a `remap = false` descriptor must
name Minecraft classes in intermediary (`net/minecraft/class_4597$class_4598`), not in the mapping
set the branch compiles against.

## Branches

| Branch | Minecraft | Mappings | Java | Loom |
|---|---|---|---|---|
| `main` | 26.1.2 | Mojang official (unobfuscated; no `mappings` line) | 25 | `net.fabricmc.fabric-loom` 1.17.x |
| `1.21.11` | 1.21.11 | yarn (obfuscated; `remapJar` produces the artifact) | 21 | `fabric-loom` 1.16.x |

The two Loom lines are not interchangeable: 1.17.x targets the unobfuscated toolchain and has no
`mappings()` at all, so it cannot consume yarn.

Both branches share the relay module verbatim and stay on the same `PROTOCOL_VERSION`, so players
on either Minecraft version see each other through one relay. Fixes are cherry-picked between them;
only the Minecraft-facing names differ.

**Minecraft 26.1+ is unobfuscated and ships Mojang's official mappings** — `Minecraft`,
`KeyMapping`, `Component`, `Identifier` (not `ResourceLocation`). The 1.21.11 branch is yarn:
`MinecraftClient`, `KeyBinding`, `Text`, `DrawContext`. Neither matches what an LLM is likely to
have memorised, and 26.1 in particular postdates most training data — `GuiGraphics` no longer
exists there, for instance. Verify against the real jars (`javap -cp <jar> <class>`, or the
readable official sources in the Gradle cache) rather than guessing.

## Build

```
./gradlew build          # the mod jar, into build/libs/
./gradlew :relay:test    # the relay's routing/scope tests
./gradlew runClient      # dev client
```

Every phase ends with `./gradlew build` passing; never commit code that does not compile.
