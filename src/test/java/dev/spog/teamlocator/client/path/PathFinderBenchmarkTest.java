package dev.spog.teamlocator.client.path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A long route through a roofed maze, the case that was slow: about 280 blocks as the crow flies,
 * with pillars everywhere. Counts positions looked at rather than timing anything, so it cannot
 * flake on a busy machine; the counts are what the time scales with.
 */
class PathFinderBenchmarkTest {
    private static final int SIZE = 160;

    /** Floor at 0, roof at 4, and a pillar wherever a hash of the column says so. */
    private static final class Maze implements Terrain {
        @Override
        public boolean loaded(int x, int z) {
            return Math.abs(x) <= SIZE && Math.abs(z) <= SIZE;
        }

        @Override
        public Cell cell(int x, int y, int z) {
            if (!loaded(x, z) || y < -4) {
                return Cell.VOID;
            }
            if (y <= 0 || y >= 4) {
                return Cell.SOLID;
            }
            int h = (x * 73856093) ^ (z * 19349663);
            h ^= h >>> 13;
            h *= 0x5bd1e995;
            h ^= h >>> 15;
            boolean wall = (h & 0xFF) < 90 && !(Math.abs(x) <= 1 && Math.abs(z) <= 1);
            return wall ? Cell.SOLID : Cell.AIR;
        }
    }

    private static PathFinder run(double weight) {
        PathFinder search = new PathFinder(new Maze(), -100, 1, -100, 100, 1, 100, 400_000, weight, true);
        while (search.step(128) == PathFinder.State.RUNNING) {
            // keep going
        }
        assertEquals(PathFinder.State.FOUND, search.state());
        return search;
    }

    @Test
    void quickSearchFindsANearlyAsGoodRouteLookingAtFarLess() {
        PathFinder exact = run(1.0);
        PathFinder quick = run(2.0);
        assertTrue(quick.visited() * 10 < exact.visited(),
                "quick looked at " + quick.visited() + ", exact at " + exact.visited());
        assertTrue(quick.pathCost() <= exact.pathCost() * 1.15,
                "quick route costs " + quick.pathCost() + ", best " + exact.pathCost());
        assertTrue(exact.pathCost() <= quick.pathCost(), "the exact route is never the longer one");
    }
}
