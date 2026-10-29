package dev.spog.teamlocator.client.path;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pathfinder on small made-up worlds: a stone floor at y=0 with walls, holes and drops built on
 * it, and nothing loaded outside a 41-block square.
 */
class PathFinderTest {
    private static final int SIZE = 20;
    private static final Cell BOTTOM_SLAB = new Cell(new double[] {0, 0, 0, 1, 0.5, 1}, false, false, false, false);
    /** A post through the middle, like iron bars or a fence: too narrow to walk past, but in the way. */
    private static final Cell POST = new Cell(new double[] {0.4375, 0, 0.4375, 0.5625, 1, 0.5625}, false, false, false, false);
    private static final Cell LADDER = new Cell(new double[] {0, 0, 0, 0.1875, 1, 1}, false, true, false, false);

    private static final class Grid implements Terrain {
        final Map<Long, Cell> cells = new HashMap<>();

        @Override
        public boolean loaded(int x, int z) {
            return Math.abs(x) <= SIZE && Math.abs(z) <= SIZE;
        }

        @Override
        public Cell cell(int x, int y, int z) {
            if (!loaded(x, z) || y < -4) {
                return Cell.VOID;
            }
            Cell c = cells.get(PathFinder.key(x, y, z));
            if (c != null) {
                return c;
            }
            return y <= 0 ? Cell.SOLID : Cell.AIR;
        }

        Grid set(int x, int y, int z, Cell c) {
            cells.put(PathFinder.key(x, y, z), c);
            return this;
        }

        /** A wall across the whole world at {@code x}, from the floor up to {@code height}. */
        Grid wall(int x, int height) {
            for (int z = -SIZE; z <= SIZE; z++) {
                for (int y = 1; y <= height; y++) {
                    set(x, y, z, Cell.SOLID);
                }
            }
            return this;
        }
    }

    private static PathFinder run(Grid grid, int sx, int sz, int gx, int gz) {
        PathFinder search = new PathFinder(grid, sx, 1, sz, gx, 1, gz, 50_000);
        while (search.step(1000) == PathFinder.State.RUNNING) {
            // keep going
        }
        return search;
    }

    private static long pearls(List<PathFinder.Point> path) {
        return path.stream().filter(PathFinder.Point::pearl).count();
    }

    @Test
    void walksStraightAcrossOpenGround() {
        PathFinder search = run(new Grid(), 0, 0, 10, 0);
        assertEquals(PathFinder.State.FOUND, search.state());
        List<PathFinder.Point> path = search.path();
        assertEquals(0, pearls(path));
        assertEquals(10, path.size(), "one point a block, ending next to the goal");
        assertEquals(1.0, path.get(0).y(), 1e-9, "standing on top of the floor");
    }

    @Test
    void pearlsThroughAOneBlockHole() {
        // A wall the player cannot get past, with a hole only a block high at their feet.
        Grid grid = new Grid().wall(5, 4).set(5, 1, 0, Cell.AIR);
        PathFinder search = run(grid, 0, 0, 10, 0);
        assertEquals(PathFinder.State.FOUND, search.state());
        assertEquals(1, pearls(search.path()));
        PathFinder.Point landed = search.path().stream().filter(PathFinder.Point::pearl).findFirst().orElseThrow();
        assertEquals(6.5, landed.x(), 1e-9, "lands just past the wall, not further on");
    }

    @Test
    void pearlsThroughAGapSmallerThanABlock() {
        // A slab with a full block above leaves half a block between them: a pearl fits, nobody else.
        Grid grid = new Grid().wall(5, 4).set(5, 1, 0, BOTTOM_SLAB);
        PathFinder search = run(grid, 0, 0, 10, 0);
        assertEquals(PathFinder.State.FOUND, search.state());
        assertEquals(1, pearls(search.path()));
    }

    @Test
    void noRouteWhenTheHoleIsBarred() {
        Grid grid = new Grid().wall(5, 4).set(5, 1, 0, POST);
        PathFinder search = run(grid, 0, 0, 10, 0);
        assertEquals(PathFinder.State.FAILED, search.state(), () -> "found " + search.path());
    }

    @Test
    void walksThroughADoorwayRatherThanPearling() {
        Grid grid = new Grid().wall(5, 4).set(5, 1, 0, Cell.AIR).set(5, 2, 0, Cell.AIR);
        PathFinder search = run(grid, 0, 0, 10, 0);
        assertEquals(PathFinder.State.FOUND, search.state());
        assertEquals(0, pearls(search.path()));
    }

    @Test
    void walksRoundWhenThatIsQuickerThanAPearl() {
        // A doorway two blocks to the side is a short detour; the pearl hole is straight ahead.
        Grid grid = new Grid().wall(5, 4).set(5, 1, 0, Cell.AIR)
                .set(5, 1, 2, Cell.AIR).set(5, 2, 2, Cell.AIR);
        PathFinder search = run(grid, 0, 0, 10, 0);
        assertEquals(PathFinder.State.FOUND, search.state());
        assertEquals(0, pearls(search.path()));
    }

    @Test
    void pearlsWhenWalkingRoundIsFar() {
        // The only doorway is at the far edge, so the pearl through the hole wins.
        Grid grid = new Grid().wall(5, 4).set(5, 1, 0, Cell.AIR)
                .set(5, 1, 18, Cell.AIR).set(5, 2, 18, Cell.AIR);
        PathFinder search = run(grid, 0, 0, 10, 0);
        assertEquals(PathFinder.State.FOUND, search.state());
        assertEquals(1, pearls(search.path()));
    }

    @Test
    void dropsThreeBlocksButNotFive() {
        // A pit dug into the floor, with the goal at its bottom. Three deep is a safe drop; five is not.
        for (int depth : new int[] {3, 5}) {
            Grid grid = new Grid();
            for (int x = 3; x <= 8; x++) {
                for (int z = -2; z <= 2; z++) {
                    for (int y = 0; y > -depth; y--) {
                        grid.set(x, y, z, Cell.AIR);
                    }
                }
            }
            PathFinder search = new PathFinder(grid, 0, 1, 0, 6, 1 - depth, 0, 50_000);
            while (search.step(1000) == PathFinder.State.RUNNING) {
                // keep going
            }
            if (depth == 3) {
                assertEquals(PathFinder.State.FOUND, search.state());
            } else {
                assertEquals(PathFinder.State.FAILED, search.state(), "a five-block drop is never taken");
            }
        }
    }

    @Test
    void climbsALadder() {
        // A raised platform four blocks up, reached only by a ladder on its side.
        Grid grid = new Grid();
        for (int x = 5; x <= 10; x++) {
            for (int z = -SIZE; z <= SIZE; z++) {
                for (int y = 1; y <= 4; y++) {
                    grid.set(x, y, z, Cell.SOLID);
                }
            }
        }
        for (int y = 1; y <= 4; y++) {
            grid.set(4, y, 0, LADDER);
        }
        PathFinder search = new PathFinder(grid, 0, 1, 0, 8, 5, 0, 50_000);
        while (search.step(1000) == PathFinder.State.RUNNING) {
            // keep going
        }
        assertEquals(PathFinder.State.FOUND, search.state());
        assertTrue(search.path().stream().anyMatch(p -> p.x() == 4.5 && p.y() >= 3), "goes up the ladder");
        assertFalse(search.path().stream().anyMatch(PathFinder.Point::pearl));
    }
}
