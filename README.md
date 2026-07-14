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

The mod must be installed on **both the server and all clients** — the server relays position and
ping payloads between mutually trusting players.

## Building

```
./gradlew build
```

The jar is produced in `build/libs/`.
