# TeamLocator

A team communication mod for Minecraft PvP, built on Fabric. Trusted players share their live
coordinates on a HUD overlay, so your team always knows where everyone is — and when someone is
under attack.

## Features

- **Live coordinate sharing** — trusted players' positions appear on your HUD in real time
- **Trust lists** — a global trust list plus a per-server trust list, switchable per server
- **Per-player visibility toggles** — hide individual teammates from your HUD without untrusting them
- **Block list** — suppress attack pings from specific players to prevent ping spam
- **Attack ping** — press a keybind when you're being attacked and your entry flashes red on your
  teammates' HUDs
- **Sharing toggle** — turn off broadcasting your own coordinates at any time
- **Configurable HUD position**

## Requirements

- Minecraft **26.1.2**
- Fabric Loader **>= 0.19**
- **Fabric API**
- **Java 25**
- Optional: [Mod Menu](https://modrinth.com/mod/modmenu) for in-game config access

The mod is **client-only** and works on **any Minecraft server** — coordinates and pings travel
through a small relay service you host (see [relay/README.md](relay/README.md)), never through the
Minecraft server. Each teammate installs the mod and points it at the same relay URL in the config.
Identity on the relay is verified against Mojang's session server, so nobody can impersonate a
teammate; trust gating is enforced on the relay, so a patched client can't see anyone who didn't
share with them.

## Building

```
./gradlew build
```

The jar is produced in `build/libs/`.

## Docs

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — how the mod, the relay, identity, and trust gating
  fit together, and what differs between the `main` (26.1.2) and `1.21.11` branches
- [docs/SETUP.md](docs/SETUP.md) — hosting your own relay
