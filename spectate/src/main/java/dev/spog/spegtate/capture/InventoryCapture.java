package dev.spog.spegtate.capture;

import dev.spog.spegtate.SpectateClient;
import dev.spog.spegtate.net.SpectateProtocol;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Sends the target's inventory to its viewers, so they can open a read-only copy of it.
 *
 * <p>Built here from the target's own live inventory rather than by forwarding the server's
 * inventory packets. Those packets describe <em>the receiving player</em>: they carry a sync id
 * and slot indices relative to whatever screen handler that client has open, so a viewer
 * applying them would overwrite its own inventory and fight its spectator state -- the same
 * reason health, abilities and held-slot packets are excluded from the capture. Reading the
 * inventory directly also means one source of truth for all 41 slots, rather than reassembling
 * them from a mixture of full-container and single-slot updates.
 *
 * <p><b>Sent only when something changes.</b> A player's inventory is static most of the time,
 * so a snapshot is compared against the last one sent and nothing goes on the wire when they
 * match. Durability ticking down on a held tool does count as a change, which is correct -- a
 * viewer watching someone mine should see it -- but it means a frame per damage tick while
 * mining, which is still far less than the terrain already being streamed.
 *
 * <p>Nothing is sent at all while no viewer exists, so a target with no spectators pays only
 * the cost of the equality check.
 */
public final class InventoryCapture {
    /**
     * The last snapshot sent, kept to avoid resending an unchanged inventory.
     *
     * <p>Stacks are copied into it: the live ones are mutated in place by the game, so holding
     * references would compare a stack against itself and never detect a change.
     */
    private List<ItemStack> lastSent;

    /**
     * Build a frame for the target's current inventory, or null if it has not changed.
     *
     * <p>Called on the client thread, where the inventory may safely be read.
     */
    public ByteBuffer frameIfChanged(Minecraft mc) {
        Player player = mc.player;
        if (player == null) {
            return null;
        }
        ClientPacketListener listener = mc.getConnection();
        if (listener == null) {
            return null;
        }

        List<ItemStack> now = snapshot(player);
        if (unchanged(now)) {
            return null;
        }
        ByteBuffer frame = encode(now, listener);
        if (frame != null) {
            // Only remembered once it is actually encodable, so a failure retries next tick
            // rather than silently pinning a snapshot that was never sent.
            lastSent = now;
        }
        return frame;
    }

    /** Forget the last snapshot, so the next call sends a full frame. */
    public void reset() {
        lastSent = null;
    }

    /**
     * The 41 player slots in vanilla numbering: 0-8 hotbar, 9-35 main, 36-39 armour, 40 offhand.
     *
     * <p>Read through the player's own inventory rather than a screen handler, so it is the same
     * set whatever screen the target happens to have open.
     */
    private static List<ItemStack> snapshot(Player player) {
        List<ItemStack> out = new ArrayList<>(SpectateProtocol.INVENTORY_SLOTS);
        var inventory = player.getInventory();
        for (int slot = 0; slot < SpectateProtocol.INVENTORY_SLOTS; slot++) {
            ItemStack stack = slot < inventory.getContainerSize() ? inventory.getItem(slot) : ItemStack.EMPTY;
            out.add(stack.isEmpty() ? ItemStack.EMPTY : stack.copy());
        }
        return out;
    }

    private boolean unchanged(List<ItemStack> now) {
        if (lastSent == null || lastSent.size() != now.size()) {
            return false;
        }
        for (int i = 0; i < now.size(); i++) {
            // matches rather than ==: the snapshot holds copies, and a stack whose count or
            // damage changed must register as different.
            if (!ItemStack.matches(lastSent.get(i), now.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Encode the non-empty slots.
     *
     * <p>Only non-empty ones go on the wire -- an inventory is mostly air, and the reader treats
     * an absent slot as empty. Each stack is encoded with the game's own item codec against the
     * session registries, so components travel correctly without this mod knowing anything about
     * item formats.
     */
    private static ByteBuffer encode(List<ItemStack> slots, ClientPacketListener listener) {
        try {
            StreamCodec<RegistryFriendlyByteBuf, ItemStack> codec = ItemStack.OPTIONAL_STREAM_CODEC;
            List<byte[]> encoded = new ArrayList<>();
            List<Integer> indices = new ArrayList<>();
            int total = 0;
            for (int slot = 0; slot < slots.size(); slot++) {
                ItemStack stack = slots.get(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                ByteBuf buf = Unpooled.buffer();
                try {
                    RegistryFriendlyByteBuf registryBuf =
                            new RegistryFriendlyByteBuf(buf, listener.registryAccess());
                    codec.encode(registryBuf, stack);
                    byte[] bytes = new byte[registryBuf.readableBytes()];
                    registryBuf.readBytes(bytes);
                    encoded.add(bytes);
                    indices.add(slot);
                    total += 2 + 4 + bytes.length;
                } finally {
                    buf.release();
                }
            }
            ByteBuffer frame = ByteBuffer.allocate(1 + 2 + total);
            frame.put(SpectateProtocol.TYPE_INVENTORY);
            frame.putShort((short) encoded.size());
            for (int i = 0; i < encoded.size(); i++) {
                frame.putShort(indices.get(i).shortValue());
                frame.putInt(encoded.get(i).length);
                frame.put(encoded.get(i));
            }
            return frame.flip();
        } catch (RuntimeException e) {
            // An un-encodable stack must not break the target's own game or the session; the
            // viewer simply keeps the previous inventory.
            SpectateClient.LOGGER.debug("spectate: could not encode the inventory", e);
            return null;
        }
    }

    /**
     * Read a frame back into slot contents, for the viewer.
     *
     * @return the 41 slots, empty where the frame named none, or null if it could not be read
     */
    public static List<ItemStack> decode(ByteBuffer frame, ClientPacketListener listener) {
        try {
            List<ItemStack> out = new ArrayList<>(SpectateProtocol.INVENTORY_SLOTS);
            for (int i = 0; i < SpectateProtocol.INVENTORY_SLOTS; i++) {
                out.add(ItemStack.EMPTY);
            }
            int count = frame.getShort() & 0xFFFF;
            StreamCodec<RegistryFriendlyByteBuf, ItemStack> codec = ItemStack.OPTIONAL_STREAM_CODEC;
            for (int i = 0; i < count; i++) {
                int slot = frame.getShort() & 0xFFFF;
                int length = frame.getInt();
                byte[] bytes = new byte[length];
                frame.get(bytes);
                if (slot >= out.size()) {
                    // A frame from a newer build with more slots: skip what cannot be placed
                    // rather than failing the whole inventory.
                    continue;
                }
                ByteBuf buf = Unpooled.wrappedBuffer(bytes);
                RegistryFriendlyByteBuf registryBuf =
                        new RegistryFriendlyByteBuf(buf, listener.registryAccess());
                out.set(slot, codec.decode(registryBuf));
            }
            return out;
        } catch (RuntimeException e) {
            SpectateClient.LOGGER.debug("spectate: could not read an inventory frame", e);
            return null;
        }
    }
}
