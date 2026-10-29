package dev.spog.teamlocator.client.path;

/**
 * What the pathfinder needs to know about one block, with no Minecraft types in it, so the search
 * can be tested on a made-up world and is the same code on every Minecraft version.
 *
 * @param boxes     the block's collision boxes in block-local coordinates, six values per box:
 *                  {@code minX, minY, minZ, maxX, maxY, maxZ}
 * @param water     the block holds water, so a player swims through it rather than standing
 * @param climbable a ladder, vine or similar, which a player can climb in either direction
 * @param hazard    damaging or trapping (lava, fire, magma, cobweb...): never stood in or on
 * @param door      a door or gate the player opens, so its boxes do not stop the body; they still
 *                  stop an ender pearl
 */
public record Cell(double[] boxes, boolean water, boolean climbable, boolean hazard, boolean door) {
    private static final double[] NONE = new double[0];

    public static final Cell AIR = new Cell(NONE, false, false, false, false);
    public static final Cell SOLID = new Cell(new double[] {0, 0, 0, 1, 1, 1}, false, false, false, false);
    /** Below the world or in a chunk that is not loaded: nothing can be there. */
    public static final Cell VOID = new Cell(NONE, false, false, true, false);

    public Cell {
        if (boxes == null) {
            boxes = NONE;
        }
    }

    public int boxCount() {
        return boxes.length / 6;
    }
}
