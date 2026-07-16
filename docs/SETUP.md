# TeamLocator — Full Setup Tutorial

From zero to a working team HUD on any Minecraft server. Three parts: **(A)** test everything on
your own PC first, **(B)** set up the VPS relay, **(C)** get your teammates connected.

---

## Part A — Test locally first (10 minutes, free)

Do this before renting anything, so you know the mod and relay work.

1. **Build both jars** (on your PC, in the project folder):
   ```
   ./gradlew build
   ```
   - Mod: `build/libs/teamlocator-0.1.0+26.1.2.jar`
   - Relay: `relay/build/libs/teamlocator-relay-all.jar`

2. **Start the relay on your PC** — with a Java 25 runtime. If plain `java` gives
   `UnsupportedClassVersionError`, your PATH points at an older Java; call JDK 25 explicitly
   (IntelliJ keeps its JDKs under `%USERPROFILE%\.jdks`):
   ```powershell
   & "$env:USERPROFILE\.jdks\openjdk-25.0.2\bin\java" -jar relay\build\libs\teamlocator-relay-all.jar --port 8080
   ```
   You should see `TeamLocator relay listening on /0.0.0.0:8080`. Leave it running.

3. **Install the mod** into your Minecraft:
   - Install the Fabric Loader (>= 0.19) for Minecraft 26.1.2 if you haven't.
   - Put in your `mods` folder: the TeamLocator jar, **Fabric API**, and (recommended) **Mod Menu**.

4. **Configure:** launch the game, join any multiplayer server, then
   Mod Menu → TeamLocator → set **Relay URL** to:
   ```
   ws://localhost:8080
   ```
   Watch the relay's console — within a few seconds you should see
   `<your-uuid> (<your-name>) authenticated in scope '<ip:port>'`.
   That line means the Mojang auth handshake worked end-to-end. The scope is the Minecraft server
   you are on, as the resolved address your client is actually connected to: two players only see
   each other when theirs match, so it is the first thing to compare if they cannot.

5. **Test with a friend** (they do steps 3–4 pointing at `ws://YOUR-PC-IP:8080`, which needs a port
   forward — or just wait for the VPS). Add each other in the trust list (the player must be on the
   same server, visible in tab), keep **Share My Coordinates: ON**, and their row should appear on
   your HUD. Press **R** (rebindable in vanilla Controls) to flash red on their HUD.

---

## Part B — The VPS relay

### B1. Rent the VPS

- Any provider: Hetzner (~€4/mo), Vultr/DigitalOcean (~$5/mo), Netcup, OVH, or Oracle Cloud's
  free tier. Smallest tier is plenty (1 vCPU / 1 GB RAM).
- OS image: **Ubuntu 24.04 LTS**.
- Pick a region near where your group plays.
- Note the server's **public IPv4 address** from the provider dashboard.

### B2. Get a (sub)domain for TLS

Let's Encrypt won't issue certificates for bare IPs, so you need a name:

- **Free:** go to https://www.duckdns.org, sign in, create a subdomain (e.g. `myteam.duckdns.org`)
  and set its IP to your VPS's IPv4.
- **Own domain:** add an `A` record (e.g. `relay.yourdomain.com`) pointing at the VPS IP.

### B3. SSH in and update

From PowerShell on your PC (Windows 10+ has ssh built in):
```
ssh root@YOUR.VPS.IP
```
Then on the VPS:
```bash
apt update && apt upgrade -y
```

### B4. Install Java 25 (Temurin)

```bash
apt install -y wget gpg apt-transport-https
mkdir -p /etc/apt/keyrings
wget -qO- https://packages.adoptium.net/artifactory/api/gpg/key/public \
  | gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg
echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(lsb_release -cs) main" \
  > /etc/apt/sources.list.d/adoptium.list
apt update && apt install -y temurin-25-jre
java -version   # should print 25.x
```

### B5. Upload the relay jar

Back on **your PC** (PowerShell, from the project folder):
```
scp relay/build/libs/teamlocator-relay-all.jar root@YOUR.VPS.IP:/tmp/
```

On the **VPS**, create a dedicated user and move it into place:
```bash
useradd -r -m -d /opt/teamlocator teamlocator
mv /tmp/teamlocator-relay-all.jar /opt/teamlocator/
chown -R teamlocator:teamlocator /opt/teamlocator
```

### B6. Run it as a service

```bash
cat > /etc/systemd/system/teamlocator-relay.service <<'EOF'
[Unit]
Description=TeamLocator Relay
After=network.target

[Service]
ExecStart=/usr/bin/java -jar /opt/teamlocator/teamlocator-relay-all.jar --host 127.0.0.1 --port 8080
Restart=always
User=teamlocator

[Install]
WantedBy=multi-user.target
EOF

systemctl enable --now teamlocator-relay
systemctl status teamlocator-relay   # should say "active (running)"
```
`--host 127.0.0.1` means the relay only listens locally — the outside world talks to Caddy, which
handles TLS.

### B7. Install Caddy (TLS / wss://)

```bash
apt install -y debian-keyring debian-archive-keyring apt-transport-https curl
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' \
  | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' \
  > /etc/apt/sources.list.d/caddy-stable.list
apt update && apt install -y caddy
```

Configure it (replace the domain with yours from B2):
```bash
cat > /etc/caddy/Caddyfile <<'EOF'
myteam.duckdns.org {
    reverse_proxy localhost:8080
}
EOF

systemctl reload caddy
```
Caddy fetches a Let's Encrypt certificate automatically on first request.

### B8. Firewall

```bash
apt install -y ufw
ufw allow OpenSSH
ufw allow 80/tcp    # Let's Encrypt certificate issuance
ufw allow 443/tcp   # wss:// traffic
ufw enable
```
(Oracle Cloud only: also open 80/443 in the cloud console's Security List.)

### B9. Verify

```bash
journalctl -u teamlocator-relay -f
```
Leave that running, and from the game set the Relay URL (next part) — you should see
`authenticated in scope ...` lines appear as teammates connect.

---

## Part C — Every teammate's setup

Each person on the team does this (including you):

1. **Install:** Fabric Loader (>= 0.19) for Minecraft 26.1.2, then into `mods/`:
   TeamLocator jar, Fabric API, Mod Menu. (You need a normal, paid Microsoft/Mojang account —
   identity is verified with Mojang; cracked launchers can't authenticate.)

2. **Set the relay URL:** Mod Menu → TeamLocator → Relay URL:
   ```
   wss://myteam.duckdns.org
   ```
   (Everyone uses the same URL.)

3. **Join the same Minecraft server** as your teammates.

4. **Trust each other:** Mod Menu → TeamLocator → pick your list with **Active List**
   (Global = applies on every server; This Server = just this one), type a teammate's name in
   **Add Player** and click Add (they must be on the server, visible in tab). **Trust is
   one-directional** — you see people who trusted *you*, so everyone adds everyone.

5. **Play.** Trusted teammates' faces + coordinates appear on your HUD (dimension shows only when
   they're in a different one). Press **R** when you're attacked — your row flashes red on their
   HUDs. Adjust the HUD position with the two sliders; hide yourself from one player with their
   row's Visible/Hidden toggle, or from everyone with **Share My Coordinates: OFF**. Add ping
   spammers to the **Block List**.

---

## Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| Relay log shows nothing when you join | Relay URL empty or wrong; check for typos, must start `ws://` or `wss://` |
| `auth-fail: mojang did not verify this session` | Cracked/offline account, or very stale login — restart the launcher to refresh the token |
| `Mojang joinServer failed, will retry` in game log | Transient Mojang outage — it retries automatically with backoff |
| Teammate on HUD but frozen, then vanishes after ~10 s | They lost their relay connection; it reconnects automatically |
| You see them, they don't see you | They haven't trusted you back (trust is one-directional), or your sharing toggle is off |
| Certificate errors on `wss://` | DNS not pointing at the VPS yet, or port 80 blocked so Let's Encrypt can't issue |
| You see a teammate on one MC server but not another | Expected — you only see teammates on the server you are both currently on. Also check which trust list is Active: a per-server list is separate from the global one and starts out empty |
| Both on the **same** server, mutually trusted, still invisible to each other | A bug — not expected, so report it. Scope is keyed on the connection's resolved `ip:port`, so you should share a scope however each of you typed the address. Builds before this keyed on the typed address, which split a hostname-joiner from an IP-joiner on any server using a non-default port |

**Updating the relay later:** build, then

```bash
scp relay/build/libs/teamlocator-relay-all.jar root@YOUR.VPS.IP:/tmp/
ssh root@YOUR.VPS.IP
mv /tmp/teamlocator-relay-all.jar /opt/teamlocator/
chown teamlocator:teamlocator /opt/teamlocator/teamlocator-relay-all.jar
systemctl restart teamlocator-relay
systemctl status teamlocator-relay --no-pager   # want "active (running)"
```

Stage through `/tmp` and re-`chown`: the service runs as `teamlocator`, so `scp`-ing straight into
`/opt/teamlocator/` as root leaves a jar the service cannot read, and it fails on restart.

Restarting drops every connected client. They reconnect on their own (retrying on a backoff floored
at 15s), so a restart alone needs no announcement.

**Ship the clients with it** whenever a change touches the wire: the relay rejects a client whose
`protocolVersion` differs, and a client on an older scope key lands in a scope of its own where
nobody can see it. Both are silent from the player's side — they just see an empty HUD — so relay
and clients go out together.
