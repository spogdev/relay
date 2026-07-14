# TeamLocator — Fabric mod for Minecraft 26.1.2

Team communication mod for PvP: trusted players share live coordinates on a HUD, with mutual-trust
gating, per-server/global trust lists, per-player visibility toggles, a block list, and an
"I'm being attacked" ping keybind that flashes the sender red on teammates' HUDs.

## CRITICAL: This is NOT the Minecraft/Fabric you know from training data

Minecraft 26.1+ is **unobfuscated** and ships **Mojang's official mappings**. Yarn no longer exists.
Class names follow Mojang conventions (`Minecraft`, `KeyMapping`, `GuiGraphics`, `Component`,
`ServerPlayer`, `LocalPlayer`) — NOT Yarn names (`MinecraftClient`, `KeyBinding`, `DrawContext`).
Some classes were also renamed since older Mojang mappings, e.g.:

- `net.minecraft.resources.Identifier` (NOT `ResourceLocation`)
- Keybinds: `net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper` (NOT `KeyBindingHelper`)
- `HoverEvent` is a sealed interface with fixed subtypes

**When unsure about any API, do not guess from training data.** Verify against:
1. The known-working reference project: `C:\Users\Stan\IdeaProjects\ChatImage-26.1.2` (same MC version, same toolchain)
2. Decompiled/attached sources in the Gradle cache (`~/.gradle/caches/`) — Minecraft 26.1 ships
   readable official sources; fabric-api and modmenu jars can be inspected directly
3. `javap -classpath <jar> <class>` for quick signature checks

## Toolchain (known-working, taken from the reference project)

- Gradle 9.5.1 wrapper, **JDK 25** required (toolchain is pinned; `C:\Users\Stan\.jdks\openjdk-25.0.2`)
- Loom plugin: `net.fabricmc.fabric-loom` version `1.17.11` (new plugin id; no remapping, no `remapJar`
  task — plain `jar` produces the final artifact)
- No `mappings` dependency line in build.gradle (official mappings are implicit)
- fabric-loader `0.19.2`, fabric-api `0.149.0+26.1.2` (depend on the **full** fabric-api artifact;
  per-module `fabricApi.module(...)` pulls are brittle in 26.1)
- Mod Menu `18.0.0-alpha.8` from `https://maven.terraformersmc.com/releases`
- Gradle 9: archive name via `base { archivesName = ... }`, not the `java` extension

## Build & run

```
./gradlew build          # produces build/libs/teamlocator-<version>.jar
./gradlew runClient      # dev client
./gradlew runServer      # dev server
```

## Architecture

- **mod id**: `teamlocator`, base package `dev.spog.teamlocator`
- `environment: "*"` — the mod has a server component that relays position/ping payloads between
  mutually-trusting players. Custom payloads via `CustomPacketPayload` + `StreamCodec` +
  `PayloadTypeRegistry` / `ClientPlayNetworking` / `ServerPlayNetworking`.
- Client config (trust lists, toggles, HUD position) lives client-side in
  `config/teamlocator.json`; trust decisions are enforced by what each client chooses to send.
- Single source set: `src/main/java` (client-only classes guarded with `@Environment(EnvType.CLIENT)`),
  matching the reference project layout.

## Working agreements

- Orchestrator delegates: **deep-reasoner** (opus) for architecture/debugging/algorithms,
  **fast-worker** (sonnet) for boilerplate/mechanical edits. Definitions in `.claude/agents/`.
- Every phase ends with `./gradlew build` passing. Never commit code that doesn't compile.
- Keep protocol codecs and their Java records in one file per payload so they can't drift apart.
