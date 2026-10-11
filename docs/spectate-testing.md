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

1. **Player B** (the target) joins a server and plays normally. Nothing else to
   do -- the addon streams on request.
2. **B must have A trusted in Relay.** The addon reads Relay's own trust list;
   there is no separate permission and no prompt. Trust is not automatically
   mutual, so check the direction: for A to watch B, **B** must trust **A**.
3. **Player A** (the viewer) stays at the main menu, clicks **Multiplayer**, then
   the **Spectate** button in the top-left corner.
4. A picks B from the list.

A session cannot be started from in-game: it replaces the client's world, so
starting one while playing would disconnect the player. The menu says so if the
world is loaded.

While spectating, the two keybinds under Controls -> Relay Spectate work
(reopen the menu, stop spectating); they do nothing at other times, because
Minecraft only dispatches keys while a world is loaded.

### What success looks like

- A loads into B's world and sees it from B's eyes, live
- B sees **"A teammate is watching you"** at the top of their screen
- Terrain extends about 10 chunks around B

### What to capture if it fails

Client logs from **both** machines (`.minecraft/logs/latest.log`), grepped for
`spectate`. The addon logs every phase, so the last line before it stops says
which stage failed. The viewer's expected sequence:

```
spectate: awaiting handshake from <uuid>
spectate host: intention PLAY
spectate host: replayed N configuration packets
spectate host: viewer entered game phase
spectate host: announced level chunks loaded
spectate: camera attached to target
```

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
