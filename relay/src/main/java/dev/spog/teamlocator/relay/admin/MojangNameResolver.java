package dev.spog.teamlocator.relay.admin;

import com.mojang.authlib.GameProfileRepository;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import com.mojang.authlib.yggdrasil.response.NameAndId;

import java.net.Proxy;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves names through Mojang's public profile API, so an admin can ban a player who is not
 * currently connected. Only consulted after the session registry comes up empty — a connected
 * player's UUID is one the relay verified itself and is always preferred.
 */
public final class MojangNameResolver implements NameResolver {
    private final GameProfileRepository profiles;

    public MojangNameResolver() {
        this.profiles = new YggdrasilAuthenticationService(Proxy.NO_PROXY).createProfileRepository();
    }

    @Override
    public UUID resolve(String name) throws Unavailable {
        try {
            Optional<NameAndId> found = profiles.findProfileByName(name);
            return found.map(NameAndId::id).orElse(null);
        } catch (RuntimeException e) {
            // authlib surfaces transport failures as unchecked exceptions; treat them as "unknown"
            // rather than "no such player", so an admin isn't told a real account doesn't exist.
            throw new Unavailable(e);
        }
    }
}
