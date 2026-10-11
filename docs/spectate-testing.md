# Relay Spectate — first test

Built for **26.1.2 only**. Ports to 26.2 / 26.3 / 1.21.11 come after this works.

## What it does

A viewer watches a teammate's world live, from the server list, without joining
that server. The target's client forwards the packets its own server sends it;
the viewer's client joins an in-process loopback server that replays them.

## Artifacts

In `dist/spectate/`:

| File | Goes where |
|---|---|
| `relay-0.3.1+26.1.2.jar` | both players' mods folder (unchanged from before) |
| `relay-spectate-0.1.0+26.1.2.jar` | both players' mods folder |
| `relay-spectate-service-all.jar` | the VPS |

Both players need **both** mod jars. The addon declares `relay` as a hard
dependency and will refuse to load without it.

## Running the service

It is a separate process from the main relay, on its own port. Needs Java 25.

```bash
java -jar relay-spectate-service-all.jar --port 8081
```

Behind a reverse proxy, the client expects the path `/spectate`. Caddy, alongside
the existing relay block:

```
relay.spog.dev {
    reverse_proxy /spectate 127.0.0.1:8081
    reverse_proxy 127.0.0.1:8080
}
```

The client derives the service address from Relay's configured address plus port
8081, so if both run on the same host behind the same proxy there is nothing to
configure. To point it elsewhere, set `serviceAddress` in
`config/relayspectate.json`.

## Testing it

Two accounts on two machines, both with both jars, both with the service
reachable.

1. **Player B** (the target) joins a server and plays normally.
2. **B must have A trusted.** The addon reads Relay's own trust list — no
   separate permission. Trust is standing, so there is no prompt.
3. **Player A** (the viewer) binds `Open Spectate Menu` under Controls →
   Relay Spectate, then presses it. From the main menu works; that is the point.
4. A picks B from the list.

### What success looks like

- A's screen loads into B's world and shows B's surroundings, live.
- B sees **"A teammate is watching you"** at the top of their screen.
- Terrain extends roughly 10 chunks around B and stops.

### What to capture if it fails

The client log, both sides. Grep for `spectate` — the addon logs every phase
transition. The most likely failures and what they look like:

| Symptom | Probable cause |
|---|---|
| A disconnects immediately, decoder error in log | handshake replay out of order, or phase codec mismatch |
| `no handshake to send; viewer will not load` | B's handshake was not recorded — B may have been connected before the addon loaded |
| A loads into an empty/void world | handshake worked, live stream is not arriving or is being filtered out |
| Visible holes at the view edge | expected — see below |
| `spectate: not connected to the service` | service unreachable, or Mojang auth failed |

## Known rough edges

**The login packet carries the target server's view distance.** It is replayed
verbatim, so A's client expects more chunks than the 10-chunk capture sends.
Missing chunks should render as nothing rather than breaking, but holes at the
edge are likely. If it looks bad, the fix is to rewrite that field rather than
replay it.

**The camera is not locked yet.** A joins as its own player entity in B's world,
so A will be a free camera rather than pinned to B. That is the next piece. It
also means this build does not yet have the property that makes the feature
defensible — right now A can look wherever they like within the streamed radius.

**Nothing is verified in-game.** The routing is tested (7 tests, real sockets,
all passing) and both jars build, but the rendering path has never been run.
Expect at least one round of fixes.

## Bandwidth

Roughly 2–5 MB when a session starts, then ~5–30 KB/s while watching, per
viewer, in and out of the VPS. One session is negligible; ten concurrent is a
few Mbps.
