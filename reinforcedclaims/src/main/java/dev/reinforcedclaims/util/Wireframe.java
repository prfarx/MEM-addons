package dev.reinforcedclaims.util;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

// Reduces a set of blocks to the shortest set of outline segments: exposed faces
// only, seams between blocks in the set dropped, collinear runs merged.
public class Wireframe {

    public enum Axis {X, Y, Z}

    protected static final Axis[] AXES = Axis.values();
    private static final int[] SIGNS = {-1, 1};

    // --- edge packing ---------------------------------------------------------------------------

    // Biases mapping world coordinates onto unsigned fields: 26 bits X/Z, 12 bits Y.
    private static final int XZ_BIAS = 1 << 25;
    private static final int Y_BIAS = 1 << 11;
    private static final long M26 = (1L << 26) - 1;
    private static final long M12 = (1L << 12) - 1;

    // Masks off the free coordinate, leaving the two that pin the edge's line.
    private static final long[] LINE_MASK = {~M26, ~M12, ~M26};

    // Packs an edge's corner, free coordinate in the low bits so sorting groups collinear runs.
    protected static long pack(Axis axis, int x, int y, int z) {
        long xb = (x + XZ_BIAS) & M26;
        long yb = (y + Y_BIAS) & M12;
        long zb = (z + XZ_BIAS) & M26;
        long key = switch (axis) {
            case X -> (zb << 38) | (yb << 26) | xb;
            case Y -> (xb << 38) | (zb << 12) | yb;
            case Z -> (xb << 38) | (yb << 26) | zb;
        };
        return key ^ Long.MIN_VALUE;
    }

    protected static int edgeX(Axis axis, long key) {
        long k = key ^ Long.MIN_VALUE;
        return switch (axis) {
            case X -> (int) (k & M26) - XZ_BIAS;
            case Y, Z -> (int) ((k >>> 38) & M26) - XZ_BIAS;
        };
    }

    protected static int edgeY(Axis axis, long key) {
        long k = key ^ Long.MIN_VALUE;
        return switch (axis) {
            case X, Z -> (int) ((k >>> 26) & M12) - Y_BIAS;
            case Y -> (int) (k & M12) - Y_BIAS;
        };
    }

    protected static int edgeZ(Axis axis, long key) {
        long k = key ^ Long.MIN_VALUE;
        return switch (axis) {
            case X -> (int) ((k >>> 38) & M26) - XZ_BIAS;
            case Y -> (int) ((k >>> 12) & M26) - XZ_BIAS;
            case Z -> (int) (k & M26) - XZ_BIAS;
        };
    }

    // The coordinate an edge runs along, from its packed key.
    private static int freeCoord(Axis axis, long key) {
        return switch (axis) {
            case X -> edgeX(axis, key);
            case Y -> edgeY(axis, key);
            case Z -> edgeZ(axis, key);
        };
    }

    // Packed edges, split by axis: a packed corner already fills all 64 bits.
    protected static final class EdgeSet {
        private final LongOpenHashSet[] byAxis = {
                new LongOpenHashSet(), new LongOpenHashSet(), new LongOpenHashSet()
        };

        protected LongOpenHashSet axis(Axis axis) {
            return byAxis[axis.ordinal()];
        }

        protected boolean add(Axis axis, int x, int y, int z) {
            return byAxis[axis.ordinal()].add(pack(axis, x, y, z));
        }

        protected boolean add(Axis axis, long key) {
            return byAxis[axis.ordinal()].add(key);
        }

        protected void addAll(EdgeSet other) {
            for (Axis axis : AXES) {
                byAxis[axis.ordinal()].addAll(other.byAxis[axis.ordinal()]);
            }
        }

        // Drops every edge the other set already holds.
        protected void subtract(EdgeSet other) {
            for (Axis axis : AXES) {
                byAxis[axis.ordinal()].removeAll(other.byAxis[axis.ordinal()]);
            }
        }

        protected boolean isEmpty() {
            for (LongOpenHashSet set : byAxis) {
                if (!set.isEmpty()) {
                    return false;
                }
            }
            return true;
        }
    }

    // Whether a position holds a full solid cube.
    public interface Occupancy {
        boolean at(int x, int y, int z);

        // As at(), for callers visiting each position once.
        default boolean probe(int x, int y, int z) {
            return at(x, y, z);
        }

        // Whether this column is loaded.
        default boolean loaded(int x, int z) {
            return true;
        }
    }

    // A run of unit edges along one axis from a lattice corner.
    public record Segment(Axis axis, int x, int y, int z, int length) {
    }

    protected Wireframe() {
    }

    // Whether both columns are loaded, so comparing them means something.
    protected static boolean comparable(Occupancy occupied, int x1, int z1, int x2, int z2) {
        return occupied.loaded(x1, z1) && occupied.loaded(x2, z2);
    }

    // Every boundary edge of these blocks, seams between them excluded.
    protected static EdgeSet candidates(Set<BlockPos> blocks, Occupancy occupied) {
        EdgeSet keep = new EdgeSet();
        BlockPos.Mutable probe = new BlockPos.Mutable();
        for (BlockPos pos : blocks) {
            int x = pos.getX();
            int y = pos.getY();
            int z = pos.getZ();
            for (Axis normal : AXES) {
                for (int sign : SIGNS) {
                    int nx = x + (normal == Axis.X ? sign : 0);
                    int ny = y + (normal == Axis.Y ? sign : 0);
                    int nz = z + (normal == Axis.Z ? sign : 0);
                    if (!comparable(occupied, x, z, nx, nz) || covered(blocks, occupied, nx, ny, nz, probe)) {
                        continue;
                    }
                    addFace(keep, x, y, z, normal, sign, blocks, occupied, probe);
                }
            }
        }
        return keep;
    }

    // The four edges of one exposed face.
    private static void addFace(EdgeSet keep, int x, int y, int z, Axis normal, int sign,
                                Set<BlockPos> blocks, Occupancy occupied, BlockPos.Mutable probe) {
        switch (normal) {
            case X -> {
                int fx = x + (sign > 0 ? 1 : 0);
                addEdge(keep, Axis.Y, fx, y, z, x, y, z - 1, normal, sign, blocks, occupied, probe);
                addEdge(keep, Axis.Y, fx, y, z + 1, x, y, z + 1, normal, sign, blocks, occupied, probe);
                addEdge(keep, Axis.Z, fx, y, z, x, y - 1, z, normal, sign, blocks, occupied, probe);
                addEdge(keep, Axis.Z, fx, y + 1, z, x, y + 1, z, normal, sign, blocks, occupied, probe);
            }
            case Y -> {
                int fy = y + (sign > 0 ? 1 : 0);
                addEdge(keep, Axis.X, x, fy, z, x, y, z - 1, normal, sign, blocks, occupied, probe);
                addEdge(keep, Axis.X, x, fy, z + 1, x, y, z + 1, normal, sign, blocks, occupied, probe);
                addEdge(keep, Axis.Z, x, fy, z, x - 1, y, z, normal, sign, blocks, occupied, probe);
                addEdge(keep, Axis.Z, x + 1, fy, z, x + 1, y, z, normal, sign, blocks, occupied, probe);
            }
            case Z -> {
                int fz = z + (sign > 0 ? 1 : 0);
                addEdge(keep, Axis.X, x, y, fz, x, y - 1, z, normal, sign, blocks, occupied, probe);
                addEdge(keep, Axis.X, x, y + 1, fz, x, y + 1, z, normal, sign, blocks, occupied, probe);
                addEdge(keep, Axis.Y, x, y, fz, x - 1, y, z, normal, sign, blocks, occupied, probe);
                addEdge(keep, Axis.Y, x + 1, y, fz, x + 1, y, z, normal, sign, blocks, occupied, probe);
            }
        }
    }

    // Keeps the edge unless the neighbour is in the same outline with its matching face exposed.
    private static void addEdge(EdgeSet keep, Axis edgeAxis, int ex, int ey, int ez,
                                int nx, int ny, int nz, Axis normal, int sign,
                                Set<BlockPos> blocks, Occupancy occupied, BlockPos.Mutable probe) {
        probe.set(nx, ny, nz);
        if (blocks.contains(probe)) {
            int ox = nx + (normal == Axis.X ? sign : 0);
            int oy = ny + (normal == Axis.Y ? sign : 0);
            int oz = nz + (normal == Axis.Z ? sign : 0);
            if (!covered(blocks, occupied, ox, oy, oz, probe)) {
                return;
            }
        }
        keep.add(edgeAxis, ex, ey, ez);
    }

    // Whether this position hides the face pointing at it. Membership counts on its own,
    // so a multi-block object draws one outline rather than a box per part.
    private static boolean covered(Set<BlockPos> blocks, Occupancy occupied, int x, int y, int z,
                                   BlockPos.Mutable probe) {
        probe.set(x, y, z);
        return blocks.contains(probe) || occupied.at(x, y, z);
    }

    // Folds one axis' edges into runs, in one pass over the sorted keys.
    protected static List<Segment> merge(Axis axis, LongOpenHashSet edges) {
        if (edges.isEmpty()) {
            return List.of();
        }
        long[] keys = edges.toLongArray();
        Arrays.sort(keys);
        long mask = LINE_MASK[axis.ordinal()];
        List<Segment> segments = new ArrayList<>();
        long runKey = keys[0];
        int runStart = freeCoord(axis, runKey);
        int length = 1;
        for (int i = 1; i < keys.length; i++) {
            long key = keys[i];
            int start = freeCoord(axis, key);
            if ((key & mask) == (runKey & mask) && start == runStart + length) {
                length++;
                continue;
            }
            segments.add(segment(axis, runKey, runStart, length));
            runKey = key;
            runStart = start;
            length = 1;
        }
        segments.add(segment(axis, runKey, runStart, length));
        return segments;
    }

    // Every axis of an edge set, merged.
    protected static List<Segment> merge(EdgeSet edges) {
        List<Segment> segments = new ArrayList<>();
        for (Axis axis : AXES) {
            segments.addAll(merge(axis, edges.axis(axis)));
        }
        return segments;
    }

    // The inverse of merge: a run back down to its unit edges.
    protected static void explode(Segment segment, EdgeSet into) {
        Axis axis = segment.axis();
        for (int i = 0; i < segment.length(); i++) {
            into.add(axis,
                    segment.x() + (axis == Axis.X ? i : 0),
                    segment.y() + (axis == Axis.Y ? i : 0),
                    segment.z() + (axis == Axis.Z ? i : 0));
        }
    }

    // Rebuilds a run's origin from any edge's packed key plus the run's start.
    private static Segment segment(Axis axis, long key, int start, int length) {
        return switch (axis) {
            case X -> new Segment(Axis.X, start, edgeY(axis, key), edgeZ(axis, key), length);
            case Y -> new Segment(Axis.Y, edgeX(axis, key), start, edgeZ(axis, key), length);
            case Z -> new Segment(Axis.Z, edgeX(axis, key), edgeY(axis, key), start, length);
        };
    }

}
