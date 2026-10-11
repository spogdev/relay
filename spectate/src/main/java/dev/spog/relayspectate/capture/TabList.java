package dev.spog.relayspectate.capture;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.PropertyMap;
import dev.spog.relayspectate.SpectateClient;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.RemoteChatSession;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Entry;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Rebuilds the target's tab list so a viewer can render players at all.
 *
 * <p>This is not the cosmetic fix it appears to be. {@code ClientPacketListener} builds a
 * {@code RemotePlayer} from the tab-list profile, and refuses outright without one:
 *
 * <pre>
 *   if (type == EntityType.PLAYER) {
 *       PlayerInfo info = getPlayerInfo(packet.getUUID());
 *       if (info == null) {
 *           LOGGER.warn("Server attempted to add player prior to sending player info ...");
 *           return null;        // the entity is discarded entirely
 *       }
 *       return new RemotePlayer(level, info.getProfile());
 *   }
 * </pre>
 *
 * <p>So a viewer with an empty tab list sees no players, including the target -- which also leaves
 * the camera nothing to attach to. The viewer's own log reports the same cause from the other
 * side, once per player per thirty seconds: "Ignoring player info update for unknown player ...".
 *
 * <p>The packet is assembled by writing vanilla's own wire format and decoding it with vanilla's
 * codec, rather than by reflecting into a constructor. Every public constructor and
 * {@code createPlayerInitializing} take {@code Collection<ServerPlayer>}, which a client does not
 * have for anybody, and while the {@code Entry} record is publicly constructible there is no
 * public path from entries to a packet.
 */
public final class TabList {
    private TabList() {
    }

    /**
     * A tab-list packet listing every player the target can see.
     *
     * <p>Must be sent before any entity packet: the client needs the entry to exist before the
     * spawn arrives, which is the ordering a real server guarantees and the error above complains
     * about.
     *
     * @return the packet, or null if there is nothing to send or it could not be built
     */
    public static ClientboundPlayerInfoUpdatePacket of(Minecraft mc) {
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) {
            return null;
        }
        List<Entry> entries = new ArrayList<>();
        java.util.Set<java.util.UUID> listed = new java.util.HashSet<>();
        for (PlayerInfo info : connection.getListedOnlinePlayers()) {
            if (info.getProfile() != null) {
                listed.add(info.getProfile().id());
            }
        }
        // Every online player, not just the listed ones. A listed-only tab list omits anyone a
        // vanish plugin or similar has hidden from the list, and an omitted player cannot be
        // rendered at all -- createEntityFromPacket discards the spawn. The listed flag below
        // still distinguishes them, so an unlisted player stays out of the viewer's tab UI while
        // existing as an entity.
        for (PlayerInfo info : connection.getOnlinePlayers()) {
            GameProfile profile = info.getProfile();
            if (profile == null) {
                continue;
            }
            profile = withTextures(profile);
            GameType mode = info.getGameMode();
            entries.add(new Entry(
                    profile.id(),
                    profile,
                    listed.contains(profile.id()),
                    info.getLatency(),
                    mode == null ? GameType.SURVIVAL : mode,
                    info.getTabListDisplayName(),
                    info.showHat(),
                    info.getTabListOrder(),
                    // No chat session. The viewer must not be handed signing data for someone
                    // else's account, and the only thing a null session disables is signature
                    // verification on chat the viewer never receives -- CaptureFilter drops all
                    // of it.
                    null));
        }
        if (entries.isEmpty()) {
            return null;
        }
        // Named, not just counted. The target failing to appear in its own tab list is the one
        // omission that would present as "entities work but players do not", since the target is
        // the player a viewer most expects to see -- and it would be invisible in a bare count.
        java.util.UUID self = mc.player == null ? null : mc.player.getUUID();
        boolean includesSelf = self != null
                && entries.stream().anyMatch(e -> self.equals(e.profileId()));
        // The texture count is reported because a profile with no "textures" property yields a
        // default skin no matter how correctly the packet is built -- the client resolves skins
        // from that property alone. That is a different fault from a malformed packet and needs a
        // different fix, so the two are worth telling apart from one log line.
        long withTextures = entries.stream()
                .filter(e -> e.profile().properties().containsKey("textures"))
                .count();
        SpectateClient.LOGGER.info(
                "spectate: tab list has {} player(s) ({} listed), includes self: {},"
                        + " with textures: {}",
                entries.size(), listed.size(), includesSelf, withTextures);
        try {
            return encodeThenDecode(connection, entries);
        } catch (RuntimeException e) {
            SpectateClient.LOGGER.warn("spectate: could not build the tab list", e);
            return null;
        }
    }

    /**
     * Profiles already resolved to include a "textures" property, keyed by id.
     *
     * <p>Held across calls because the resolution is a network fetch: the tab list is rebuilt
     * every time a viewer starts, and refetching every profile each time would both stall and
     * hammer the session service. Never large -- one entry per player seen on the server.
     */
    private static final java.util.Map<java.util.UUID, GameProfile> RESOLVED =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Set when a fetch has completed since the last tab list was sent.
     *
     * <p>Read and cleared by {@link #resolvedSinceLastSend()}, which the capture tick polls while
     * streaming so a late-arriving skin reaches a viewer that is already watching.
     */
    private static final java.util.concurrent.atomic.AtomicBoolean RESOLVED_SINCE_LAST_SEND =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** True once, if any profile's textures have arrived since the last tab list was sent. */
    public static boolean resolvedSinceLastSend() {
        return RESOLVED_SINCE_LAST_SEND.getAndSet(false);
    }

    /** Ids currently being fetched, so a slow fetch is not started twice. */
    private static final java.util.Set<java.util.UUID> FETCHING =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Returns the profile with its "textures" property, fetching it if need be.
     *
     * <p>This is what makes skins work, and it is not optional. A client resolves a skin from the
     * profile's "textures" property alone: {@code SkinManager.get} calls
     * {@code getPackedTextures(profile)}, which reads that property out of the profile rather than
     * fetching anything by id, and falls back to {@code DefaultPlayerSkin.get(profile)} -- the
     * UUID-derived default -- when it is absent.
     *
     * <p>The profiles a client holds for other players usually do carry it, because the server
     * sent it. The profile a client holds for <em>itself</em> frequently does not: the client knows
     * who it is from its own {@code User}, which holds a name and id and no textures at all. So the
     * target would relay a complete tab list in which the one player a viewer is actually watching
     * had no skin.
     *
     * <p>Fetched off-thread and used on the next rebuild rather than waited for: the fetch is an
     * HTTP call, and this runs on the client thread while a viewer is waiting for terrain. The
     * first tab list for a given player therefore ships without textures, and the capture tick
     * resends it once the fetch completes.
     *
     * <p>This explains the pattern of "some players have skins and some do not": the ones that do
     * were sent by the server with textures already attached and never needed any of this.
     */
    private static GameProfile withTextures(GameProfile profile) {
        if (profile.properties().containsKey("textures")) {
            return profile;
        }
        GameProfile resolved = RESOLVED.get(profile.id());
        if (resolved != null) {
            return resolved;
        }
        fetchTextures(profile);
        return profile;
    }

    /** Starts a one-off background fetch of a profile's textures. */
    private static void fetchTextures(GameProfile profile) {
        java.util.UUID id = profile.id();
        if (!FETCHING.add(id)) {
            return;
        }
        // ioPool, not backgroundExecutor: the resolver blocks on an HTTP request, and the
        // background executor is sized for CPU work.
        net.minecraft.util.Util.ioPool().execute(() -> {
            try {
                // The session service directly, not services().profileResolver(). The resolver
                // reachable from the client is wrapped by LocalPlayerResolver, whose fetchById
                // returns ClientPacketListener.getPlayerInfo(id).getProfile() when the id is in
                // the tab list -- which it always is for the players being captured. So asking
                // the resolver for a tab-list player's profile hands back the very textureless
                // profile being worked around, the filter below rejected it, and nothing was ever
                // resolved or resent. fetchProfile goes to the service and returns the full
                // profile, textures included.
                var result = Minecraft.getInstance().services().sessionService()
                        .fetchProfile(id, true);
                java.util.Optional.ofNullable(result)
                        .map(com.mojang.authlib.yggdrasil.ProfileResult::profile)
                        .filter(full -> full.properties().containsKey("textures"))
                        .ifPresent(full -> {
                            RESOLVED.put(id, full);
                            // The tab list already sent went out without this profile's textures,
                            // since the fetch is asynchronous and the list is built the moment a
                            // viewer starts. Nothing would ever send a second one, so the skin
                            // would stay default for the whole session and only be right for the
                            // next one. This asks for a resend.
                            RESOLVED_SINCE_LAST_SEND.set(true);
                        });
            } catch (RuntimeException e) {
                // Warned, not swallowed. A failure here is invisible in the world -- the player
                // simply keeps a default skin -- which is exactly the kind of silent failure that
                // has already cost several rounds of diagnosis on this feature.
                SpectateClient.LOGGER.warn("spectate: could not resolve textures for {}", id, e);
            } finally {
                FETCHING.remove(id);
            }
        });
    }

    /**
     * Rebuilds a tab-list packet from a modified entry list.
     *
     * <p>Used by the viewer to drop its own entry from a forwarded list, which it must do because
     * a client takes its game mode from its tab-list entry and would otherwise be pulled out of
     * spectator by the target's. The packet is immutable, and the same encode-then-decode route
     * {@link #of} uses is the only public way to build one.
     *
     * @param actions the action set from the original packet, written in the original's order
     * @param entries the entries to keep
     * @return the packet, or null if it could not be built
     */
    public static ClientboundPlayerInfoUpdatePacket rebuild(
            EnumSet<Action> actions, List<Entry> entries, Minecraft mc) {
        ClientPacketListener connection = mc.getConnection();
        if (connection == null || entries.isEmpty()) {
            return null;
        }
        try {
            return encodeThenDecode(connection, actions, entries);
        } catch (RuntimeException e) {
            SpectateClient.LOGGER.warn("spectate: could not rebuild the tab list", e);
            return null;
        }
    }

    /**
     * The actions this class writes.
     *
     * <p>Only these are ever put in the set, so an action added by a future version cannot end up
     * in a packet that {@link #writeField} would silently write nothing for.
     * {@code ADD_PLAYER} is the one that carries the profile, and therefore the one that makes
     * player entities possible at all; the rest are what a real server sends alongside it, and
     * omitting one leaves the client with a default rather than with nothing.
     */
    private static final EnumSet<Action> ACTIONS = EnumSet.of(
            Action.ADD_PLAYER,
            Action.UPDATE_LISTED,
            Action.UPDATE_LATENCY,
            Action.UPDATE_GAME_MODE,
            Action.UPDATE_DISPLAY_NAME,
            Action.UPDATE_HAT,
            Action.UPDATE_LIST_ORDER);

    /**
     * Builds the packet through vanilla's codec.
     *
     * <p>Writes the fields in the order the packet's own {@code write} does -- the action set, then
     * the entries as a collection, each prefixed by its profile id -- and reads the result back
     * with {@code STREAM_CODEC}, so the decoded packet is indistinguishable from a real one.
     */
    private static ClientboundPlayerInfoUpdatePacket encodeThenDecode(
            ClientPacketListener connection, List<Entry> entries) {
        return encodeThenDecode(connection, ACTIONS, entries);
    }

    /** As above, for a caller that must preserve an existing packet's action set. */
    private static ClientboundPlayerInfoUpdatePacket encodeThenDecode(
            ClientPacketListener connection, EnumSet<Action> actions, List<Entry> entries) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
                Unpooled.buffer(), connection.registryAccess());
        buf.writeEnumSet(actions, Action.class);
        buf.writeCollection(entries, (out, entry) -> {
            RegistryFriendlyByteBuf typed = (RegistryFriendlyByteBuf) out;
            typed.writeUUID(entry.profileId());
            for (Action action : actions) {
                writeField(typed, action, entry);
            }
        });
        return ClientboundPlayerInfoUpdatePacket.STREAM_CODEC.decode(buf);
    }

    /** Writes one action's payload for one entry, mirroring vanilla's per-action writers. */
    private static void writeField(RegistryFriendlyByteBuf buf, Action action, Entry entry) {
        switch (action) {
            case ADD_PLAYER -> {
                buf.writeUtf(entry.profile().name(), 16);
                // Collected first so the count and the writes cannot disagree. PropertyMap is a
                // multimap: size() counts keys while values() iterates values, and a profile with
                // two properties under one key would have written a count of one followed by two
                // properties -- desynchronising the whole packet, which is read positionally. The
                // skin lives in the "textures" property, so getting this wrong costs the skin and
                // then everything after it.
                List<com.mojang.authlib.properties.Property> properties =
                        new ArrayList<>(entry.profile().properties().values());
                buf.writeVarInt(properties.size());
                for (com.mojang.authlib.properties.Property property : properties) {
                    buf.writeUtf(property.name());
                    buf.writeUtf(property.value());
                    // Written unsigned, deliberately. A signature only verifies against Mojang's
                    // public key for a profile fetched straight from the session service, and the
                    // target's may not be: Essential, for one, replaces signatures with the
                    // literal string "trusted" and accepts that in its own verifier. Forwarding
                    // such a signature made the viewer's vanilla verifier try to RSA-verify five
                    // bytes -- "Bad signature length: got 5 but was expecting 512" -- mark the
                    // property INVALID and discard the skin, which is why skins stayed default
                    // while the texture payload itself was arriving perfectly intact.
                    //
                    // Dropping it yields UNSIGNED rather than INVALID, and SkinManager rejects
                    // only INVALID, so the skin loads. Nothing is lost by this: the viewer is
                    // being handed a world by a peer it has already chosen to trust, and a
                    // texture signature is not what establishes that trust.
                    //
                    // writeBoolean(false) is what writeNullable writes for an absent value: the
                    // present/absent flag, and then nothing.
                    buf.writeBoolean(false);
                }
            }
            case INITIALIZE_CHAT -> buf.writeNullable(entry.chatSession(),
                    RemoteChatSession.Data::write);
            case UPDATE_GAME_MODE -> buf.writeVarInt(entry.gameMode().getId());
            case UPDATE_LISTED -> buf.writeBoolean(entry.listed());
            case UPDATE_LATENCY -> buf.writeVarInt(entry.latency());
            case UPDATE_DISPLAY_NAME -> buf.writeNullable(entry.displayName(),
                    (b, name) -> ComponentSerialization.TRUSTED_STREAM_CODEC
                            .encode((RegistryFriendlyByteBuf) b, name));
            case UPDATE_HAT -> buf.writeBoolean(entry.showHat());
            case UPDATE_LIST_ORDER -> buf.writeVarInt(entry.listOrder());
        }
    }
}
