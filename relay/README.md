# TeamLocator Relay

A standalone WebSocket service that routes coordinates and attack pings between mutually-trusting
TeamLocator players — **independently of the Minecraft server they're on**. This is what lets the mod
work on any server, including public ones that don't (and can't) install a Fabric mod.

## Why this exists

Minecraft custom packets are relayed by the game server, so a server without the mod simply drops
them. The only way to share data between two modded clients on an arbitrary server is an out-of-game
side channel — this relay, which you host.

## Security model

- **Identity is verified with Mojang.** On connect, the relay issues a random nonce; the client calls
  `joinServer(profileId, accessToken, nonce)` against Mojang's session server, and the relay then
  calls `hasJoinedServer(name, nonce, ip)`. Only if Mojang vouches does the connection get a UUID —
  **taken from Mojang's response, never from anything the client claimed.** A griefer cannot connect
  as someone else's UUID to harvest their teammates' coordinates.
- **Trust gating is enforced here, not on the client.** A position is sent to a viewer only if the
  owner's uploaded trust set contains that viewer. Patching a client cannot make it see more.
- **Pings require mutual trust** and are dropped if the recipient blocked the sender.
- **Scoped per Minecraft server.** You only ever see teammates who are on the *same* MC server as
  you, so a global trust list can't leak your position across unrelated servers.

Known limits, stated plainly:
- The relay operator (you) can see the coordinates it routes — it must, in order to route them.
- On **offline-mode / cracked** servers there is no real Mojang account to verify, so identity
  can't be established this way.
- Coordinates are self-reported by each client, so a client could report false positions. Fine for a
  cooperative friend group; not a defense against a malicious teammate.

## Build

```
./gradlew :relay:shadowJar
```

Produces `relay/build/libs/teamlocator-relay-all.jar` (self-contained).

## Run on a VPS

```
java -jar teamlocator-relay-all.jar --port 8080
```

Flags: `--port` (default 8080), `--host` (default 0.0.0.0).

Run it under systemd so it survives logout:

```ini
# /etc/systemd/system/teamlocator-relay.service
[Unit]
Description=TeamLocator Relay
After=network.target

[Service]
ExecStart=/usr/bin/java -jar /opt/teamlocator/teamlocator-relay-all.jar --port 8080
Restart=always
User=teamlocator

[Install]
WantedBy=multi-user.target
```

```
sudo systemctl enable --now teamlocator-relay
```

## TLS (use `wss://` for real use)

Don't terminate TLS in Java — put Caddy in front and let it handle certs automatically:

```
# /etc/caddy/Caddyfile
relay.example.com {
    reverse_proxy localhost:8080
}
```

Clients then point at `wss://relay.example.com`. Plain `ws://host:8080` is fine for local testing,
but over the internet it would send coordinates in the clear — use `wss://`.

Open the port in your firewall (or just 443 if Caddy fronts it).

## Protocol

JSON over WebSocket, one object per frame, discriminated by `type`. See
`protocol/Messages.java`. Client → relay: `hello`, `auth-response`, `trust-update`, `block-update`,
`position-update`, `ping`. Relay → client: `auth-challenge`, `auth-ok`, `auth-fail`,
`position-snapshot`, `ping-broadcast`.

## Tests

```
./gradlew :relay:test
```

Drives the real relay over real WebSockets and asserts the security properties: no leak to untrusted
viewers, mutual-trust ping gating, block-list suppression, cross-server isolation, and that an
unauthenticated socket can neither inject trust state nor read positions.
