package dev.spog.teamlocator.client.path;

/** The blocks a {@link PathFinder} searches through. */
public interface Terrain {
    /** Whether the column is loaded. The search never routes through one that is not. */
    boolean loaded(int x, int z);

    /** The block at a position; {@link Cell#VOID} outside the world. */
    Cell cell(int x, int y, int z);
}
