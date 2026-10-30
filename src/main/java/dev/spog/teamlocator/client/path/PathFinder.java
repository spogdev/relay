package dev.spog.teamlocator.client.path;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

/**
 * A* search for the quickest way a player can get from one block to another: walking, jumping up a
 * block, dropping down (a long fall costs more, for the damage), swimming, climbing, and throwing an
 * ender pearl through a gap too small to walk through.
 *
 * <p>The search is incremental: {@link #step} expands a bounded number of positions and returns, so
 * the caller can spread a long search over several ticks instead of stalling a frame. It only reads
 * the world through {@link Terrain}, and never leaves loaded chunks.
 *
 * <p><b>Positions.</b> A node is the block the player's feet are in. Its floor is the height they
 * stand at, which is not always the bottom of that block: a slab or carpet in the block raises it, and
 * standing on a fence lifts it half a block into the one above.
 *
 * <p><b>Partial blocks.</b> Whether the body fits depends on which way the player is going: a door,
 * pane or open trapdoor along one side of a block can be walked past alongside it, but not through.
 * Every collision box in the way is projected across the direction of travel, and the player fits if
 * what is left open is at least as wide as they are.
 *
 * <p><b>Pearl gaps.</b> A pearl is 0.25 blocks across, so it fits through a gap a player cannot: a
 * one-block hole in a wall, the space between a slab and the block above it, the slit beside a door
 * or a pane, and so on. From a position up against a wall the search looks straight into it at feet,
 * head and above-head height, across the whole width of the block, for a gap up to
 * {@value #PEARL_REACH} blocks deep that a pearl can pass through, and links the position to where
 * the player would stand just past it. Pearling costs more than walking the same
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
    static final int SAFE_DROP = 3;
    /** The furthest fall onto solid ground a route takes; much further and it would kill. */
    static final int MAX_FALL = 20;
    /** The furthest fall into water a route takes, which does no damage at any height. */
    static final int MAX_WATER_FALL = 64;
    /** The deepest wall, in blocks, a pearl is thrown through. */
    static final int PEARL_REACH = 3;
    /** Half the pearl's size, used to fit it through a gap. */
    private static final double PEARL_HALF = 0.125;
    /**
     * Where in a block a pearl is tried, as fractions of it: these heights, and the same positions
     * across the block, so a gap is found wherever in the block it is.
     */
    private static final double[] PEARL_STEPS = {0.125, 0.25, 0.375, 0.5, 0.625, 0.75, 0.875};
    private static final int STEPS = PEARL_STEPS.length;

    /** Costs, in roughly the time it takes to walk one block. */
    private static final double WALK = 1.0;
    private static final double DIAGONAL = Math.sqrt(2.0);
    private static final double JUMP = 0.5;
    private static final double DROP_PER_BLOCK = 0.25;
    /** Extra for each block fallen past a safe drop, for the health it costs. */
    private static final double FALL_DAMAGE = 0.5;
    private static final double SWIM = 2.0;
    private static final double CLIMB = 1.5;
    /**
     * Throwing, waiting for the pearl to land, and the pearl itself, on top of the distance it
     * covers. Charged as walking that distance too, so a pearl never looks cheaper than a short way
     * round, and throwing from further back saves nothing.
     */
    private static final double PEARL = 3.0;

    private static final double EPS = 1.0e-4;

    /** Which way the body has to fit through a block: standing in it, or crossing it along X or Z. */
    private static final int ANY = 0;
    private static final int ALONG_X = 1;
    private static final int ALONG_Z = 2;

    private static final int[][] HORIZONTAL = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private final Terrain terrain;
    private final int maxNodes;
    private final double weight;
    private final boolean usePearls;
    /** Scratch space for {@link #clear}: the extents of the boxes in the way, across X and Z. */
    private double[] spansX = new double[32];
    private double[] spansZ = new double[32];
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
     * @param pearls   whether the route may pearl through gaps
     */
    public PathFinder(Terrain terrain, int sx, int sy, int sz, int gx, int gy, int gz, int maxNodes,
                      double weight, boolean pearls) {
        this.terrain = terrain;
        this.maxNodes = maxNodes;
        this.weight = weight;
        this.usePearls = pearls;
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
            int axis = diagonal ? ANY : dx != 0 ? ALONG_X : ALONG_Z;
            // Cutting a corner needs both blocks beside it to be open, or the player snags on one.
            if (diagonal && (!clear(n.x + dx, n.z, f, f + PLAYER_HEIGHT, ANY)
                    || !clear(n.x, n.z + dz, f, f + PLAYER_HEIGHT, ANY))) {
                continue;
            }
            int nx = n.x + dx;
            int nz = n.z + dz;
            // The highest position in the next column the player can reach. Anything under it is
            // shut off by the block it stands on.
            for (int ty = n.y + 1; ty >= n.y - MAX_WATER_FALL; ty--) {
                double tf = floor(nx, ty, nz);
                if (Double.isNaN(tf)) {
                    // Nothing to stand on here. Below the level they left, a solid block ends the
                    // fall: the player would have landed on top of it, which was the row above.
                    if (ty <= n.y && top(terrain.cell(nx, ty, nz)) > STEP_HEIGHT + EPS) {
                        break;
                    }
                    continue;
                }
                double rise = tf - f;
                double cost = diagonal ? DIAGONAL : WALK;
                if (rise > JUMP_HEIGHT + EPS) {
                    break;
                }
                if (rise > STEP_HEIGHT + EPS) {
                    // A jump: up in their own column, then across at the new height.
                    if (!clear(n.x, n.z, f, tf + PLAYER_HEIGHT, ANY)
                            || !clear(n.x, n.z, tf, tf + PLAYER_HEIGHT, axis)
                            || !clear(nx, nz, tf, tf + PLAYER_HEIGHT, axis)) {
                        break;
                    }
                    cost += JUMP;
                } else if (rise < -STEP_HEIGHT - EPS) {
                    // A drop: across at the height they stood, then down the next column.
                    double fall = -rise;
                    boolean water = terrain.cell(nx, ty, nz).water();
                    if ((!water && fall > MAX_FALL + EPS)
                            || !clear(n.x, n.z, f, f + PLAYER_HEIGHT, axis)
                            || !clear(nx, nz, f, f + PLAYER_HEIGHT, axis)
                            || !clear(nx, nz, tf, f + PLAYER_HEIGHT, ANY)) {
                        break;
                    }
                    cost += fall * DROP_PER_BLOCK;
                    if (!water && fall > SAFE_DROP + 0.5) {
                        cost += (fall - SAFE_DROP) * FALL_DAMAGE;
                    }
                } else {
                    // Level, or a step: out of this block and into the next, both the way they go.
                    double across = Math.max(f, tf);
                    if (!clear(n.x, n.z, across, across + PLAYER_HEIGHT, axis)
                            || !clear(nx, nz, tf, tf + PLAYER_HEIGHT, axis)) {
                        break;
                    }
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

        if (!swimming && usePearls) {
            pearls(n);
        }
    }

    /** Links {@code n} to where a pearl thrown straight ahead through a small gap would land. */
    private void pearls(Node n) {
        double f = n.floor;
        for (int d = 0; d < 4; d++) {
            int dx = HORIZONTAL[d][0];
            int dz = HORIZONTAL[d][1];
            int axis = dx != 0 ? ALONG_X : ALONG_Z;
            // Only up against a wall: a pearl is charged its distance, so throwing from further back
            // never beats walking up to it first, and most positions are ruled out here, cheaply.
            if (clear(n.x + dx, n.z + dz, f, f + PLAYER_HEIGHT, axis)) {
                continue;
            }
            for (int row = n.y; row <= n.y + 2; row++) {
                long windows = -1L >>> (64 - STEPS * STEPS);
                for (int k = 1; k <= PEARL_REACH; k++) {
                    int cx = n.x + dx * k;
                    int cz = n.z + dz * k;
                    Cell c = terrain.cell(cx, row, cz);
                    // The pearl keeps one line all the way through the gap.
                    windows &= pearlWindows(c, dx != 0);
                    if (windows == 0) {
                        break;
                    }
                    // Still a hole, not open air: the player cannot get through here, and something
                    // is beside or over the pearl's way -- a gap in a wall rather than a throw over
                    // a ledge, which the routes are not meant to suggest.
                    boolean hole = !clear(cx, cz, f, f + PLAYER_HEIGHT, axis)
                            && (c.boxCount() > 0 || terrain.cell(cx, row + 1, cz).boxCount() > 0);
                    if (!hole) {
                        break; // through the wall: the landing was the block before this one
                    }
                    int lx = n.x + dx * (k + 1);
                    int lz = n.z + dz * (k + 1);
                    for (int ty = row; ty >= row - MAX_FALL; ty--) {
                        double tf = floor(lx, ty, lz);
                        if (Double.isNaN(tf)) {
                            if (top(terrain.cell(lx, ty, lz)) > STEP_HEIGHT + EPS) {
                                break;
                            }
                            continue;
                        }
                        Node to = relax(n, lx, ty, lz, tf, PEARL + (k + 1) * WALK);
                        if (to != null) {
                            // Through the middle of the gap's length, at the opening nearest the
                            // middle of the block: where the slit actually is, not the block centre.
                            int w = centralWindow(windows);
                            double across = PEARL_STEPS[w / STEPS];
                            double along = (1 + k) / 2.0;
                            to.pearl = true;
                            to.gapX = dx != 0 ? n.x + 0.5 + dx * along : n.x + across;
                            to.gapY = row + PEARL_STEPS[w % STEPS];
                            to.gapZ = dx != 0 ? n.z + across : n.z + 0.5 + dz * along;
                        }
                        break;
                    }
                }
            }
        }
    }

    /**
     * Where a pearl flying through this block along X or Z passes without touching any of its
     * collision boxes: a bit for each pair of {@link #PEARL_STEPS} across the block and up it,
     * numbered {@code across * STEPS + up}.
     */
    private static long pearlWindows(Cell c, boolean alongX) {
        long mask = 0;
        double[] b = c.boxes();
        for (int a = 0; a < STEPS; a++) {
            double crossLo = PEARL_STEPS[a] - PEARL_HALF;
            double crossHi = PEARL_STEPS[a] + PEARL_HALF;
            for (int h = 0; h < STEPS; h++) {
                double lo = PEARL_STEPS[h] - PEARL_HALF;
                double hi = PEARL_STEPS[h] + PEARL_HALF;
                boolean free = true;
                for (int i = 0; i < b.length && free; i += 6) {
                    double crossMin = alongX ? b[i + 2] : b[i];
                    double crossMax = alongX ? b[i + 5] : b[i + 3];
                    if (crossMin < crossHi - EPS && crossMax > crossLo + EPS
                            && b[i + 1] < hi - EPS && b[i + 4] > lo + EPS) {
                        free = false;
                    }
                }
                if (free) {
                    mask |= 1L << (a * STEPS + h);
                }
            }
        }
        return mask;
    }

    /** Of the openings set in a mask, the one nearest the middle of the block. */
    private static int centralWindow(long windows) {
        int middle = STEPS / 2;
        int best = -1;
        int bestDistance = Integer.MAX_VALUE;
        for (int w = 0; w < STEPS * STEPS; w++) {
            if ((windows & (1L << w)) != 0) {
                int da = w / STEPS - middle;
                int dh = w % STEPS - middle;
                int distance = da * da + dh * dh;
                if (distance < bestDistance) {
                    best = w;
                    bestDistance = distance;
                }
            }
        }
        return best;
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
        return clear(x, z, base, base + PLAYER_HEIGHT, ANY) ? base : Double.NaN;
    }

    /**
     * Whether a player's body fits in this column between two heights: crossing it along X or Z, or
     * standing in it ({@link #ANY}, which needs room one way or the other).
     */
    private boolean clear(int x, int z, double lo, double hi, int axis) {
        if (!terrain.loaded(x, z)) {
            return false;
        }
        int bottom = (int) Math.floor(lo + EPS);
        int topCell = (int) Math.floor(hi - EPS);
        int count = 0;
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
                if (cy + b[i + 1] < hi - EPS && cy + b[i + 4] > lo + EPS) {
                    if (count * 2 + 2 > spansX.length) {
                        spansX = java.util.Arrays.copyOf(spansX, spansX.length * 2);
                        spansZ = java.util.Arrays.copyOf(spansZ, spansZ.length * 2);
                    }
                    spansX[count * 2] = b[i];
                    spansX[count * 2 + 1] = b[i + 3];
                    spansZ[count * 2] = b[i + 2];
                    spansZ[count * 2 + 1] = b[i + 5];
                    count++;
                }
            }
        }
        if (count == 0) {
            return true;
        }
        // Crossing along X, what matters is the room left across Z, and the other way about.
        return switch (axis) {
            case ALONG_X -> roomLeft(spansZ, count);
            case ALONG_Z -> roomLeft(spansX, count);
            default -> roomLeft(spansX, count) || roomLeft(spansZ, count);
        };
    }

    /**
     * Whether the spans, laid side by side across a block, leave an opening as wide as a player.
     * Sorts the spans it is given, in place.
     */
    private static boolean roomLeft(double[] spans, int count) {
        // Insertion sort by start: there are only ever a handful.
        for (int i = 1; i < count; i++) {
            double start = spans[i * 2];
            double end = spans[i * 2 + 1];
            int j = i - 1;
            while (j >= 0 && spans[j * 2] > start) {
                spans[(j + 1) * 2] = spans[j * 2];
                spans[(j + 1) * 2 + 1] = spans[j * 2 + 1];
                j--;
            }
            spans[(j + 1) * 2] = start;
            spans[(j + 1) * 2 + 1] = end;
        }
        double reached = 0;
        for (int i = 0; i < count; i++) {
            if (spans[i * 2] - reached >= PLAYER_WIDTH - EPS) {
                return true;
            }
            reached = Math.max(reached, spans[i * 2 + 1]);
        }
        return 1.0 - reached >= PLAYER_WIDTH - EPS;
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
