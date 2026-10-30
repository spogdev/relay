package dev.spog.teamlocator.client.path;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

/**
 * A* search for the quickest way a player can get from one block to another: walking, jumping up a
 * block, dropping down without fall damage, swimming, climbing, and throwing an ender pearl through
 * a gap too small to walk through.
 *
 * <p>The search is incremental: {@link #step} expands a bounded number of positions and returns, so
 * the caller can spread a long search over several ticks instead of stalling a frame. It only reads
 * the world through {@link Terrain}, and never leaves loaded chunks.
 *
 * <p><b>Positions.</b> A node is the block the player's feet are in. Its floor is the height they
 * stand at, which is not always the bottom of that block: a slab or carpet in the block raises it, and
 * standing on a fence lifts it half a block into the one above.
 *
 * <p><b>Pearl gaps.</b> A pearl is 0.25 blocks across, so it fits through a gap a player cannot: a
 * one-block hole in a wall, the space between a slab and the block above it, and so on. From a
 * position up against a wall the search looks straight into it at feet, head and above-head height
 * for a gap up to {@value #PEARL_REACH} blocks deep that a pearl can pass through, and links the
 * position to where the player would stand just past it. Pearling costs more than walking the same
 * distance, so a route only uses one when walking round is longer.
 *
 * <p><b>Speed.</b> The search can be weighted: a weight above 1 trusts the straight-line distance to
 * the goal more than the cost so far, which finds a route after looking at a small fraction of the
 * positions, at the price of one that may be a little longer than the best. The tracker runs a
 * weighted search to show a route quickly, then an unweighted one to replace it with the best.
 */
public final class PathFinder {
    public enum State { RUNNING, FOUND, FAILED }

    /**
     * A point on the route: the middle of a block, at the height the player stands. {@code pearl}
     * marks a point reached by pearling from the one before it rather than by walking, and the gap
     * is then the middle of the hole the pearl goes through.
     */
    public record Point(double x, double y, double z, boolean pearl,
                        double gapX, double gapY, double gapZ) {
        /** A point reached on foot, with no gap to go through. */
        public Point(double x, double y, double z) {
            this(x, y, z, false, Double.NaN, Double.NaN, Double.NaN);
        }
    }

    static final double PLAYER_HEIGHT = 1.8;
    /** Player width; a block part that leaves this much free beside it does not block the body. */
    static final double PLAYER_WIDTH = 0.6;
    /** The height a player walks up without jumping: a slab, a carpet, a path block. */
    static final double STEP_HEIGHT = 0.6;
    static final double JUMP_HEIGHT = 1.25;
    /** The furthest a player falls without taking damage. */
    static final int MAX_DROP = 3;
    /** The deepest wall, in blocks, a pearl is thrown through. */
    static final int PEARL_REACH = 3;
    /** Half the pearl's size, used to fit it through a gap. */
    private static final double PEARL_HALF = 0.125;
    /** Heights within a block a pearl can pass at, as fractions of the block. */
    private static final double[] PEARL_HEIGHTS = {0.125, 0.25, 0.375, 0.5, 0.625, 0.75, 0.875};

    /** Costs, in roughly the time it takes to walk one block. */
    private static final double WALK = 1.0;
    private static final double DIAGONAL = Math.sqrt(2.0);
    private static final double JUMP = 0.5;
    private static final double DROP_PER_BLOCK = 0.25;
    private static final double SWIM = 2.0;
    private static final double CLIMB = 1.5;
    /**
     * Throwing, waiting for the pearl to land, and the pearl itself, on top of the distance it
     * covers. Charged as walking that distance too, so a pearl never looks cheaper than a short way
     * round, and throwing from further back saves nothing.
     */
    private static final double PEARL = 3.0;

    private static final double EPS = 1.0e-4;

    private static final int[][] HORIZONTAL = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private final Terrain terrain;
    private final int maxNodes;
    private final double weight;
    private final LongTable<Void> floors = new LongTable<>(4096);
    private final LongTable<Node> nodes = new LongTable<>(4096);
    private final PriorityQueue<Node> open = new PriorityQueue<>();

    private final int gx;
    private final int gy;
    private final int gz;

    private State state = State.RUNNING;
    private List<Point> path = List.of();
    private double pathCost = Double.POSITIVE_INFINITY;

    private static final class Node implements Comparable<Node> {
        final int x;
        final int y;
        final int z;
        final double floor;
        double cost = Double.POSITIVE_INFINITY;
        double estimate;
        Node parent;
        boolean pearl;
        /** For a node reached by pearl, the middle of the gap the pearl goes through. */
        double gapX;
        double gapY;
        double gapZ;
        boolean closed;

        Node(int x, int y, int z, double floor) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.floor = floor;
        }

        @Override
        public int compareTo(Node o) {
            return Double.compare(estimate, o.estimate);
        }
    }

    /**
     * Prepares a search between two block positions, usually where each player's feet are. Either may
     * be a little off a standable spot (mid-jump, or in a block with an odd shape): each is moved to
     * the nearest one in a small area, and the search fails at once if there is none.
     *
     * @param maxNodes the most positions to visit before giving up on a route
     * @param weight   how far to trust the distance to the goal: 1 finds the best route, more
     *                 finds a near-best one much sooner
     */
    public PathFinder(Terrain terrain, int sx, int sy, int sz, int gx, int gy, int gz, int maxNodes,
                      double weight) {
        this.terrain = terrain;
        this.maxNodes = maxNodes;
        this.weight = weight;
        int[] start = nearestNode(sx, sy, sz);
        int[] goal = nearestNode(gx, gy, gz);
        if (start == null || goal == null) {
            this.gx = gx;
            this.gy = gy;
            this.gz = gz;
            state = State.FAILED;
            return;
        }
        this.gx = goal[0];
        this.gy = goal[1];
        this.gz = goal[2];
        Node first = node(start[0], start[1], start[2], floor(start[0], start[1], start[2]));
        first.cost = 0;
        first.estimate = weight * heuristic(first);
        open.add(first);
    }

    public State state() {
        return state;
    }

    /** The route, start first, once {@link #state} is {@link State#FOUND}; empty otherwise. */
    public List<Point> path() {
        return path;
    }

    /** What the route costs to follow, in about the time it takes to walk a block; infinite if none. */
    public double pathCost() {
        return pathCost;
    }

    /** How many positions the search has looked at so far. */
    public int visited() {
        return nodes.size();
    }

    /**
     * Runs the search for up to {@code expansions} positions.
     *
     * @return the state afterwards; {@link State#RUNNING} means call again
     */
    public State step(int expansions) {
        for (int i = 0; i < expansions && state == State.RUNNING; i++) {
            Node n = open.poll();
            if (n == null) {
                state = State.FAILED;
                break;
            }
            if (n.closed) {
                continue;
            }
            n.closed = true;
            if (Math.abs(n.x - gx) <= 1 && Math.abs(n.y - gy) <= 1 && Math.abs(n.z - gz) <= 1) {
                path = trace(n);
                pathCost = n.cost;
                state = State.FOUND;
                break;
            }
            expand(n);
            if (nodes.size() > maxNodes) {
                state = State.FAILED;
            }
        }
        return state;
    }

    private void expand(Node n) {
        Cell here = terrain.cell(n.x, n.y, n.z);
        boolean swimming = here.water();
        double f = n.floor;

        for (int[] d : HORIZONTAL) {
            int dx = d[0];
            int dz = d[1];
            boolean diagonal = dx != 0 && dz != 0;
            // Cutting a corner needs both blocks beside it to be open, or the player snags on one.
            if (diagonal && (!clear(n.x + dx, n.z, f, f + PLAYER_HEIGHT)
                    || !clear(n.x, n.z + dz, f, f + PLAYER_HEIGHT))) {
                continue;
            }
            int nx = n.x + dx;
            int nz = n.z + dz;
            // The highest position in the next column the player can reach. Anything under it is
            // shut off by the block it stands on.
            for (int ty = n.y + 1; ty >= n.y - MAX_DROP - 1; ty--) {
                double tf = floor(nx, ty, nz);
                if (Double.isNaN(tf)) {
                    continue;
                }
                double rise = tf - f;
                double cost = diagonal ? DIAGONAL : WALK;
                if (rise > JUMP_HEIGHT + EPS) {
                    break;
                }
                if (rise > STEP_HEIGHT + EPS) {
                    // A jump: the player rises in their own column before moving across.
                    if (!clear(n.x, n.z, f, tf + PLAYER_HEIGHT)) {
                        break;
                    }
                    cost += JUMP;
                } else if (rise < -STEP_HEIGHT - EPS) {
                    // A drop: the player falls down the next column from where they stood.
                    if (!clear(nx, nz, tf, f + PLAYER_HEIGHT)) {
                        break;
                    }
                    if (-rise > MAX_DROP + 0.5 + EPS && !terrain.cell(nx, ty, nz).water()) {
                        break;
                    }
                    cost += -rise * DROP_PER_BLOCK;
                }
                if (swimming || terrain.cell(nx, ty, nz).water()) {
                    cost *= SWIM;
                }
                relax(n, nx, ty, nz, tf, cost);
                break;
            }
        }

        // Straight up and down a ladder, vine or water column.
        for (int dy = -1; dy <= 1; dy += 2) {
            int ty = n.y + dy;
            Cell target = terrain.cell(n.x, ty, n.z);
            boolean moves = dy > 0
                    ? (here.climbable() || here.water()) && (target.climbable() || target.water())
                    : target.climbable() || target.water();
            if (moves) {
                double tf = floor(n.x, ty, n.z);
                if (!Double.isNaN(tf)) {
                    relax(n, n.x, ty, n.z, tf, here.water() ? SWIM : CLIMB);
                }
            }
        }

        if (!swimming) {
            pearls(n);
        }
    }

    /** Links {@code n} to where a pearl thrown straight ahead through a small gap would land. */
    private void pearls(Node n) {
        double f = n.floor;
        for (int d = 0; d < 4; d++) {
            int dx = HORIZONTAL[d][0];
            int dz = HORIZONTAL[d][1];
            // Only up against a wall: a pearl is charged its distance, so throwing from further back
            // never beats walking up to it first, and most positions are ruled out here, cheaply.
            if (clear(n.x + dx, n.z + dz, f, f + PLAYER_HEIGHT)) {
                continue;
            }
            for (int row = n.y; row <= n.y + 2; row++) {
                int heights = (1 << PEARL_HEIGHTS.length) - 1;
                for (int k = 1; k <= PEARL_REACH; k++) {
                    int cx = n.x + dx * k;
                    int cz = n.z + dz * k;
                    // The pearl keeps one height all the way through the gap.
                    heights &= pearlHeights(terrain.cell(cx, row, cz), dx != 0);
                    if (heights == 0) {
                        break;
                    }
                    // Still a hole, not open air: the player cannot walk on here, and the pearl's
                    // way is roofed over or squeezed -- a gap in a wall rather than a throw over a
                    // ledge, which the routes are not meant to suggest.
                    boolean hole = !clear(cx, cz, f, f + PLAYER_HEIGHT)
                            && (top(terrain.cell(cx, row, cz)) >= 0
                            || top(terrain.cell(cx, row + 1, cz)) >= 0);
                    if (!hole) {
                        break; // through the wall: the landing was the block before this one
                    }
                    int lx = n.x + dx * (k + 1);
                    int lz = n.z + dz * (k + 1);
                    for (int ty = row; ty >= row - MAX_DROP - 1; ty--) {
                        double tf = floor(lx, ty, lz);
                        if (!Double.isNaN(tf)) {
                            Node to = relax(n, lx, ty, lz, tf, PEARL + (k + 1) * WALK);
                            if (to != null) {
                                // The middle of the gap, at the height nearest its centre that the
                                // pearl fits through.
                                double mid = (1 + k) / 2.0;
                                to.pearl = true;
                                to.gapX = n.x + 0.5 + dx * mid;
                                to.gapY = row + PEARL_HEIGHTS[centralHeight(heights)];
                                to.gapZ = n.z + 0.5 + dz * mid;
                            }
                            break;
                        }
                    }
                }
            }
        }
    }

    /** Of the heights set in a mask, the one nearest the middle of the block. */
    private static int centralHeight(int heights) {
        int middle = PEARL_HEIGHTS.length / 2;
        for (int off = 0; off <= middle; off++) {
            if ((heights & (1 << (middle - off))) != 0) {
                return middle - off;
            }
            if (middle + off < PEARL_HEIGHTS.length && (heights & (1 << (middle + off))) != 0) {
                return middle + off;
            }
        }
        return middle;
    }

    /**
     * Which of {@link #PEARL_HEIGHTS} a pearl flying through the middle of this block, along X or Z,
     * passes at without touching any of its collision boxes, as a bit mask.
     */
    private static int pearlHeights(Cell c, boolean alongX) {
        int mask = 0;
        for (int h = 0; h < PEARL_HEIGHTS.length; h++) {
            double lo = PEARL_HEIGHTS[h] - PEARL_HALF;
            double hi = PEARL_HEIGHTS[h] + PEARL_HALF;
            boolean free = true;
            double[] b = c.boxes();
            for (int i = 0; i < b.length && free; i += 6) {
                double crossMin = alongX ? b[i + 2] : b[i];
                double crossMax = alongX ? b[i + 5] : b[i + 3];
                boolean inLine = crossMin < 0.5 + PEARL_HALF - EPS && crossMax > 0.5 - PEARL_HALF + EPS;
                boolean atHeight = b[i + 1] < hi - EPS && b[i + 4] > lo + EPS;
                if (inLine && atHeight) {
                    free = false;
                }
            }
            if (free) {
                mask |= 1 << h;
            }
        }
        return mask;
    }

    /**
     * Records a cheaper way to a position, if this is one.
     *
     * @return the position's node when its way there changed, so a pearl can be noted on it, or
     *         {@code null} when the way it already had was as cheap
     */
    private Node relax(Node from, int x, int y, int z, double floor, double step) {
        Node to = node(x, y, z, floor);
        if (to.closed) {
            return null;
        }
        double cost = from.cost + step;
        if (cost >= to.cost - EPS) {
            return null;
        }
        to.cost = cost;
        to.parent = from;
        to.pearl = false;
        to.estimate = cost + weight * heuristic(to);
        // A cheaper way to an open node is queued again rather than re-sorted in place; the stale
        // entry is skipped when it comes up, being already closed by then.
        open.add(to);
        return to;
    }

    private double heuristic(Node n) {
        double dx = n.x - gx;
        double dy = n.y - gy;
        double dz = n.z - gz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private Node node(int x, int y, int z, double floor) {
        long k = key(x, y, z);
        int slot = nodes.find(k);
        if (slot >= 0) {
            return nodes.object(slot);
        }
        Node n = new Node(x, y, z, floor);
        nodes.putObject(k, n);
        return n;
    }

    private static List<Point> trace(Node end) {
        List<Point> out = new ArrayList<>();
        for (Node n = end; n != null; n = n.parent) {
            out.add(n.pearl
                    ? new Point(n.x + 0.5, n.floor, n.z + 0.5, true, n.gapX, n.gapY, n.gapZ)
                    : new Point(n.x + 0.5, n.floor, n.z + 0.5));
        }
        Collections.reverse(out);
        return List.copyOf(out);
    }

    /** The standable position nearest a block, looking a few blocks down first, then around. */
    private int[] nearestNode(int x, int y, int z) {
        for (int r = 0; r <= 2; r++) {
            for (int dy = 1; dy >= -4; dy--) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                            continue;
                        }
                        if (!Double.isNaN(floor(x + dx, y + dy, z + dz))) {
                            return new int[] {x + dx, y + dy, z + dz};
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * The height a player stands at with their feet in this block, or NaN if they cannot stand there:
     * no floor (unless swimming or climbing), no room for their body, or something that hurts.
     */
    double floor(int x, int y, int z) {
        long k = key(x, y, z);
        int slot = floors.find(k);
        if (slot >= 0) {
            return floors.number(slot);
        }
        double f = computeFloor(x, y, z);
        floors.putNumber(k, f);
        return f;
    }

    private double computeFloor(int x, int y, int z) {
        if (!terrain.loaded(x, z)) {
            return Double.NaN;
        }
        Cell feet = terrain.cell(x, y, z);
        if (feet.hazard()) {
            return Double.NaN;
        }
        double base;
        double low = top(feet);
        if (low > STEP_HEIGHT + EPS) {
            return Double.NaN; // solid where the feet go
        }
        if (low >= 0) {
            base = y + low;
        } else {
            Cell below = terrain.cell(x, y - 1, z);
            double support = top(below);
            if (support >= 0 && !below.hazard()) {
                base = y - 1 + support;
            } else if (feet.water() || feet.climbable()) {
                base = y;
            } else {
                return Double.NaN;
            }
        }
        return clear(x, z, base, base + PLAYER_HEIGHT) ? base : Double.NaN;
    }

    /** Whether a player's body fits in this column between two heights. */
    private boolean clear(int x, int z, double lo, double hi) {
        if (!terrain.loaded(x, z)) {
            return false;
        }
        int bottom = (int) Math.floor(lo + EPS);
        int topCell = (int) Math.floor(hi - EPS);
        for (int cy = bottom; cy <= topCell; cy++) {
            Cell c = terrain.cell(x, cy, z);
            if (c.hazard()) {
                return false;
            }
            if (c.door()) {
                continue;
            }
            double[] b = c.boxes();
            for (int i = 0; i < b.length; i += 6) {
                if (blocksBody(b, i) && cy + b[i + 1] < hi - EPS && cy + b[i + 4] > lo + EPS) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The top of whatever in this block would carry or stop a player, or -1 if nothing would. */
    private static double top(Cell c) {
        if (c.door()) {
            return -1;
        }
        double top = -1;
        double[] b = c.boxes();
        for (int i = 0; i < b.length; i += 6) {
            if (blocksBody(b, i)) {
                top = Math.max(top, b[i + 4]);
            }
        }
        return top;
    }

    /**
     * Whether a box gets in a player's way. A thin part against one side of the block -- a ladder,
     * an open trapdoor -- leaves room for the player beside it, so it does not.
     */
    private static boolean blocksBody(double[] b, int i) {
        double freeX = Math.max(b[i], 1.0 - b[i + 3]);
        double freeZ = Math.max(b[i + 2], 1.0 - b[i + 5]);
        return freeX < PLAYER_WIDTH - EPS && freeZ < PLAYER_WIDTH - EPS;
    }

    static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }
}
