package dev.spog.teamlocator.relay.auth;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.yggdrasil.ProfileResult;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;

import java.net.InetAddress;
import java.net.Proxy;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Verifies a connecting client's real Minecraft identity using the same Mojang session-server
 * handshake vanilla servers use. The client calls {@code joinServer(profileId, accessToken,
 * serverId)} for a nonce we issued; we then ask Mojang {@code hasJoinedServer(name, serverId, ip)}.
 * A non-null result means Mojang vouches that the holder of that account's token performed the join,
 * so the UUID in the returned profile is authoritative — a client-claimed UUID is never trusted.
 */
public final class MojangVerifier implements Verifier {
    private final MinecraftSessionService sessionService;
    private final SecureRandom random = new SecureRandom();

    public MojangVerifier() {
        this.sessionService = new YggdrasilAuthenticationService(Proxy.NO_PROXY)
                .createMinecraftSessionService();
    }

    @Override
    public String newChallenge() {
        byte[] buf = new byte[20];
        random.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }

    @Override
    public UUID verify(String profileName, String serverId, InetAddress clientIp) throws Unavailable {
        try {
            ProfileResult result = sessionService.hasJoinedServer(profileName, serverId, clientIp);
            if (result == null) {
                return null;
            }
            GameProfile profile = result.profile();
            return profile != null ? profile.id() : null;
        } catch (AuthenticationUnavailableException e) {
            throw new Unavailable(e);
        }
    }
}
