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
        for (PlayerInfo info : connection.getListedOnlinePlayers()) {
            GameProfile profile = info.getProfile();
            if (profile == null) {
                continue;
            }
            GameType mode = info.getGameMode();
            entries.add(new Entry(
                    profile.id(),
                    profile,
                    true,
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
        try {
            return encodeThenDecode(connection, entries);
        } catch (RuntimeException e) {
            SpectateClient.LOGGER.warn("spectate: could not build the tab list", e);
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
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
                Unpooled.buffer(), connection.registryAccess());
        buf.writeEnumSet(ACTIONS, Action.class);
        buf.writeCollection(entries, (out, entry) -> {
            RegistryFriendlyByteBuf typed = (RegistryFriendlyByteBuf) out;
            typed.writeUUID(entry.profileId());
            for (Action action : ACTIONS) {
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
                PropertyMap properties = entry.profile().properties();
                buf.writeVarInt(properties.size());
                for (var property : properties.values()) {
                    buf.writeUtf(property.name());
                    buf.writeUtf(property.value());
                    buf.writeNullable(property.signature(),
                            (b, signature) -> b.writeUtf(signature));
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
