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

It is a separate process from the main relay, on its own port, bound to
localhost and reached through the existing reverse proxy. Needs Java 25.

```bash
java -jar relay-spectate-service-all.jar --host 127.0.0.1 --port 8090
```

Pick a port nothing else is using -- check with `ss -tlnp` first rather than
assuming, since a relay host tends to accumulate services in the 808x range.

The client expects the path `/spectate` on Relay's own host. In Caddy, the
specific path must come **before** the catch-all, because directives are matched
in order and a bare `reverse_proxy` first would swallow it:

```
relay.spog.dev {
    reverse_proxy /spectate 127.0.0.1:8090
    reverse_proxy localhost:8080
}
```

The client defaults to Relay's configured host with that path, so with both
behind the same proxy there is nothing to configure. `serviceAddress` in
`config/relayspectate.json` overrides the host, and may carry `host:port` for a
service exposed directly -- mainly useful for a local dev instance.

## Testing it

Two accounts on two machines, both with both jars, both with the service
reachable.

**The viewer must be at the main menu, not in a world.** A session replaces the
client's world with the target's, so it cannot start from in-game; the menu says
so if you try.

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

**The camera is locked to the target.** You see what they see, from their eyes.
You cannot move independently -- that is deliberate, since a free camera in
streamed terrain can look through walls the target cannot see.

**The view extends 10 chunks and then stops.** Terrain past that is never sent,
so the horizon ends abruptly. The client is told its view distance is 10 so it
should not expect more, but the edge will look flat.

**Nothing has been run in a real client.** The routing is tested and the join
sequence was built by reading the vanilla server's own bytecode rather than
guessing, but the rendering path has still never executed. Expect something.

## Bandwidth

Roughly 2–5 MB when a session starts, then ~5–30 KB/s while watching, per
viewer, in and out of the VPS. One session is negligible; ten concurrent is a
few Mbps.
