package dev.reinforcedclaims.util;

// Outlines a claim boundary: a vertical plane between inside and outside. An edge
// survives where the blocks either side of it disagree.
public final class BorderWireframe extends Wireframe {

    private BorderWireframe() {
    }

    // Solid/not-solid either side of the plane, as a flat bitset.
    // side 0 is the outside column, 1 the inside one.
    private static final class PlaneBits {
        private final int t0;
        private final int tCount;
        private final int y0;
        private final int yCount;
        private final long[] bits;

        PlaneBits(int t0, int tCount, int y0, int yCount) {
            this.t0 = t0;
            this.tCount = tCount;
            this.y0 = y0;
            this.yCount = yCount;
            this.bits = new long[(2 * tCount * yCount + 63) >>> 6];
        }

        private int index(int side, int t, int y) {
            return (side * tCount + (t - t0)) * yCount + (y - y0);
        }

        void set(int side, int t, int y) {
            int i = index(side, t, y);
            bits[i >>> 6] |= 1L << i;
        }

        boolean at(int side, int t, int y) {
            int i = index(side, t, y);
            return (bits[i >>> 6] & (1L << i)) != 0;
        }
    }

    // Scans one boundary plane, perpendicular to normal (never Y), and collects its edges.
    protected static void candidates(Axis normal, int planeCoord, int minT, int maxT, int minY, int maxY,
                                     Occupancy occupied, EdgeSet into) {
        if (normal == Axis.Y) {
            throw new IllegalArgumentException("BorderWireframe only supports a horizontal normal (X or Z)");
        }
        boolean[] known = knownColumns(normal, planeCoord, minT, maxT, occupied);
        PlaneBits solid = read(normal, planeCoord, minT, maxT, minY, maxY, occupied, known);

        // Horizontal edges: one per Y line, shared by the cells above and below.
        for (int t = minT; t <= maxT; t++) {
            if (!known[t - minT + 1]) {
                continue;
            }
            for (int y = minY; y <= maxY + 1; y++) {
                if (solid.at(0, t, y - 1) != solid.at(0, t, y) || solid.at(1, t, y - 1) != solid.at(1, t, y)) {
                    if (normal == Axis.X) {
                        into.add(Axis.Z, planeCoord, y, t);
                    } else {
                        into.add(Axis.X, t, y, planeCoord);
                    }
                }
            }
        }
        // Vertical edges: one per tangential line, shared by the cells either side.
        for (int t = minT; t <= maxT + 1; t++) {
            if (!known[t - minT] || !known[t - minT + 1]) {
                continue;
            }
            for (int y = minY; y <= maxY; y++) {
                if (solid.at(0, t - 1, y) != solid.at(0, t, y) || solid.at(1, t - 1, y) != solid.at(1, t, y)) {
                    if (normal == Axis.X) {
                        into.add(Axis.Y, planeCoord, y, t);
                    } else {
                        into.add(Axis.Y, t, y, planeCoord);
                    }
                }
            }
        }
    }

    // Reads both sides of the plane into a bitset. Unloaded columns stay clear.
    private static PlaneBits read(Axis normal, int planeCoord, int minT, int maxT, int minY, int maxY,
                                  Occupancy occupied, boolean[] known) {
        int t0 = minT - 1;
        int tCount = maxT - minT + 3;
        int y0 = minY - 1;
        int yCount = maxY - minY + 3;
        PlaneBits solid = new PlaneBits(t0, tCount, y0, yCount);
        for (int side = 0; side < 2; side++) {
            int normalCoord = planeCoord - 1 + side;
            for (int i = 0; i < tCount; i++) {
                if (!known[i]) {
                    continue;
                }
                int t = t0 + i;
                for (int j = 0; j < yCount; j++) {
                    int y = y0 + j;
                    boolean occupiedHere = normal == Axis.X
                            ? occupied.probe(normalCoord, y, t)
                            : occupied.probe(t, y, normalCoord);
                    if (occupiedHere) {
                        solid.set(side, t, y);
                    }
                }
            }
        }
        return solid;
    }

    // Which columns have world data on both sides; elsewhere nothing is drawn.
    private static boolean[] knownColumns(Axis normal, int planeCoord, int minT, int maxT, Occupancy occupied) {
        boolean[] known = new boolean[maxT - minT + 3];
        for (int i = 0; i < known.length; i++) {
            int t = minT - 1 + i;
            known[i] = normal == Axis.X
                    ? comparable(occupied, planeCoord - 1, t, planeCoord, t)
                    : comparable(occupied, t, planeCoord - 1, t, planeCoord);
        }
        return known;
    }
}
