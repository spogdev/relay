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
    /**
     * Iron bars joined to the wall on both sides: a thin sheet right across the hole. (A lone post
     * leaves room for a pearl beside it, as a fence post does.)
     */
    private static final Cell BARS = new Cell(new double[] {0.4375, 0, 0, 0.5625, 1, 1}, false, false, false, false);
    /** A thin panel along the -X face, like a closed door or an open trapdoor across the way. */
    private static final Cell PANEL_ACROSS_X = new Cell(new double[] {0, 0, 0, 0.1875, 1, 0.75}, false, false, false, false);
    /** A thin panel along the -Z face, running the same way as someone walking along X. */
    private static final Cell PANEL_ALONG_X = new Cell(new double[] {0, 0, 0, 1, 1, 0.1875}, false, false, false, false);
    private static final Cell LADDER = new Cell(new double[] {0, 0, 0, 0.1875, 1, 1}, false, true, false, false);

    private static final class Grid implements Terrain {
        final Map<Long, Cell> cells = new HashMap<>();

        @Override
        public boolean loaded(int x, int z) {
            return Math.abs(x) <= SIZE && Math.abs(z) <= SIZE;
        }

        @Override
        public Cell cell(int x, int y, int z) {
            if (!loaded(x, z) || y < -40) {
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
        return run(grid, sx, 1, sz, gx, 1, gz, true);
    }

    private static PathFinder run(Grid grid, int sx, int sy, int sz, int gx, int gy, int gz,
                                  boolean pearls) {
        PathFinder search = new PathFinder(grid, sx, sy, sz, gx, gy, gz, 50_000, 1.0, pearls);
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
        // The arc is drawn through the gap: the middle of the hole, which is at the player's feet.
        assertEquals(5.5, landed.gapX(), 1e-9, "the gap is in the wall");
        assertEquals(0.5, landed.gapZ(), 1e-9);
        assertEquals(1.5, landed.gapY(), 1e-9, "half way up the hole");
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
        Grid grid = new Grid().wall(5, 4).set(5, 1, 0, BARS);
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
    void dropsDownLongFallsButNotDeadlyOnes() {
        // A pit dug into the floor, with the goal at its bottom and no other way down.
        for (int depth : new int[] {5, 15, 30}) {
            Grid grid = new Grid();
            for (int x = 3; x <= 8; x++) {
                for (int z = -2; z <= 2; z++) {
                    for (int y = 0; y > -depth; y--) {
                        grid.set(x, y, z, Cell.AIR);
                    }
                }
            }
            PathFinder search = run(grid, 0, 1, 0, 6, 1 - depth, 0, true);
            if (depth < PathFinder.MAX_FALL) {
                assertEquals(PathFinder.State.FOUND, search.state(), "a " + depth + "-block fall is taken");
            } else {
                assertEquals(PathFinder.State.FAILED, search.state(), "a " + depth + "-block fall would kill");
            }
        }
    }

    @Test
    void dropsAnyDistanceIntoWater() {
        Grid grid = new Grid();
        Cell water = new Cell(null, true, false, false, false);
        for (int x = 3; x <= 8; x++) {
            for (int z = -2; z <= 2; z++) {
                for (int y = 0; y > -30; y--) {
                    grid.set(x, y, z, y > -28 ? Cell.AIR : water);
                }
            }
        }
        assertEquals(PathFinder.State.FOUND, run(grid, 0, 1, 0, 6, -28, 0, true).state());
    }

    @Test
    void pearlsThroughASlitBesideAPartialBlock() {
        // The hole holds a panel across the way that covers three quarters of it, leaving a slit a
        // quarter of a block wide down one side: room for a pearl, not a player.
        Grid grid = new Grid().wall(5, 4).set(5, 1, 0, PANEL_ACROSS_X).set(5, 2, 0, PANEL_ACROSS_X);
        PathFinder search = run(grid, 0, 0, 10, 0);
        assertEquals(PathFinder.State.FOUND, search.state());
        PathFinder.Point landed = search.path().stream().filter(PathFinder.Point::pearl).findFirst().orElseThrow();
        assertEquals(0.875, landed.gapZ(), 1e-9, "through the slit, not the middle of the block");
    }

    @Test
    void walksAlongsideAThinPanelButNotThroughOne() {
        // The same panel turned to run along the way: there is room beside it to walk past.
        Grid alongside = new Grid().wall(5, 4).set(5, 1, 0, PANEL_ALONG_X).set(5, 2, 0, PANEL_ALONG_X);
        PathFinder search = run(alongside, 0, 0, 10, 0);
        assertEquals(PathFinder.State.FOUND, search.state());
        assertEquals(0, pearls(search.path()));

        // Turned across the way and covering it all, it stops the player, and with pearls off,
        // there is no route.
        Cell across = new Cell(new double[] {0, 0, 0, 0.1875, 1, 1}, false, false, false, false);
        Grid blocked = new Grid().wall(5, 4).set(5, 1, 0, across).set(5, 2, 0, across);
        assertEquals(PathFinder.State.FAILED, run(blocked, 0, 1, 0, 10, 1, 0, false).state());
    }

    @Test
    void neverPearlsWhenPearlsAreOff() {
        Grid grid = new Grid().wall(5, 4).set(5, 1, 0, Cell.AIR);
        assertEquals(PathFinder.State.FAILED, run(grid, 0, 1, 0, 10, 1, 0, false).state());
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
        PathFinder search = run(grid, 0, 1, 0, 8, 5, 0, true);
        assertEquals(PathFinder.State.FOUND, search.state());
        assertTrue(search.path().stream().anyMatch(p -> p.x() == 4.5 && p.y() >= 3), "goes up the ladder");
        assertFalse(search.path().stream().anyMatch(PathFinder.Point::pearl));
    }
}
