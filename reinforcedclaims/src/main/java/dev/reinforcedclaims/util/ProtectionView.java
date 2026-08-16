package dev.reinforcedclaims.util;

import dev.reinforcedclaims.claim.ClaimManager;
import dev.reinforcedclaims.claim.ClaimState;
import dev.reinforcedclaims.config.Config;
import dev.reinforcedclaims.protection.ProtectionManager;
import dev.reinforcedclaims.reinforcement.Reinforcement;
import dev.reinforcedclaims.reinforcement.ReinforcementState;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.block.BlockState;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// The /clm view overlay: wireframes over reinforced blocks, claim boundaries, claim
// structures and marker rings, sent to one viewer as display entities. The scene covers
// the viewer's chunk window and height band, and each redraw diffs against what the
// client already holds. Viewer state is transient.
public final class ProtectionView {

    // --- tuning ---------------------------------------------------------------------------------

    // Vertical half-extent of the scene. Claim structures and rings are exempt.
    private static final int BORDER_HEIGHT = 16;

    // Floor on the gap between redraws.
    private static final int MIN_REDRAW_TICKS = 2;

    // How far the viewer must climb or drop before the scene is rebuilt.
    private static final int REDRAW_Y_STEP = 8;

    // Claims whose boundary is scanned per redraw, nearest first.
    private static final int MAX_CLAIMS = 16;

    // How far above a claim's tallest block its ring floats; also caps its wall height.
    private static final int MARKER_CLEARANCE = 4;

    // Display entities one viewer may hold. A line costs four.
    private static final int MAX_PIECES = 8192;

    // Thickness of a wireframe line.
    private static final float LINE = 0.04f;

    // How far a face is nudged clear of the terrain face sharing its plane, which the
    // two would otherwise fight over in the depth buffer.
    private static final double DEPTH_BIAS = 0.0001;


    // --- palette (ARGB; a text display's background takes any colour) ---------------------------

    private static final int REINFORCED = 0xFFD8_4C4C;
    private static final int CLAIM_BORDER = 0xFFCC_44CC;
    private static final int CLAIM_STRUCTURE = 0xFFF2_F2F2;

    // --- state ----------------------------------------------------------------------------------

    // One face of one line, keyed on everything affecting its look and on nothing about
    // the viewer, so a piece found again is one the client already holds.
    private record Piece(double x, double y, double z,
                         Wireframe.Axis length, Wireframe.Axis thickness, boolean flipped,
                         float span, int color, boolean seeThrough) {
    }

    // One claim's marker: footprint half-extent, ring height, and the segments drawn.
    private record Marker(int size, int ceiling, List<Wireframe.Segment> segments) {
    }

    // The chunk square the client holds: the scene's horizontal extent.
    private record Window(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {

        static Window around(ServerPlayerEntity viewer) {
            ChunkPos center = viewer.getChunkPos();
            int radius = viewer.getServer().getPlayerManager().getViewDistance();
            return new Window(center.x - radius, center.z - radius, center.x + radius, center.z + radius);
        }

        int minX() {
            return minChunkX << 4;
        }

        int maxX() {
            return (maxChunkX << 4) + 15;
        }

        int minZ() {
            return minChunkZ << 4;
        }

        int maxZ() {
            return (maxChunkZ << 4) + 15;
        }

        boolean contains(int x, int z) {
            return x >= minX() && x <= maxX() && z >= minZ() && z <= maxZ();
        }

        boolean overlaps(int fromX, int toX, int fromZ, int toZ) {
            return fromX <= maxX() && toX >= minX() && fromZ <= maxZ() && toZ >= minZ();
        }
    }

    // What one viewer's client currently holds.
    private static final class Scene {
        // Piece -> the network id it went out under.
        final Object2IntOpenHashMap<Piece> sent = new Object2IntOpenHashMap<>();
        // Markers per claim block, measured once and re-measured only when a block change
        // inside the footprint evicts the entry, so walking around never moves the ring.
        final Map<BlockPos, Marker> markers = new HashMap<>();
        RegistryKey<World> dimension;
        // What the last redraw was built for; the scene is stale once the viewer leaves it.
        Window window;
        int y;
        // Bounding box of every cached footprint, so invalidate() can reject a distant
        // block change in four comparisons.
        boolean anyMarkers;
        int markerMinX;
        int markerMinZ;
        int markerMaxX;
        int markerMaxZ;

        // Whether this column could fall inside any cached footprint.
        boolean touchesMarker(int x, int z) {
            return anyMarkers && x >= markerMinX && x <= markerMaxX && z >= markerMinZ && z <= markerMaxZ;
        }

        // Recomputes that box, once per redraw.
        void refreshMarkerBounds() {
            anyMarkers = false;
            markerMinX = Integer.MAX_VALUE;
            markerMinZ = Integer.MAX_VALUE;
            markerMaxX = Integer.MIN_VALUE;
            markerMaxZ = Integer.MIN_VALUE;
            for (Map.Entry<BlockPos, Marker> entry : markers.entrySet()) {
                BlockPos center = entry.getKey();
                int size = entry.getValue().size();
                markerMinX = Math.min(markerMinX, center.getX() - size);
                markerMaxX = Math.max(markerMaxX, center.getX() + size);
                markerMinZ = Math.min(markerMinZ, center.getZ() - size);
                markerMaxZ = Math.max(markerMaxZ, center.getZ() + size);
                anyMarkers = true;
            }
        }
        // Server tick the last redraw went out on.
        int lastDraw;
        // Set when something the scene draws moves; cleared by the redraw that answers it.
        volatile boolean stale;
    }

    // A built scene, before it is diffed against what was already sent.
    private static final class Build {
        final Set<Piece> pieces = new LinkedHashSet<>();
        // Marker ring and corner drops, as whole segments.
        final List<Wireframe.Segment> markers = new ArrayList<>();
        int reinforced;
        int borderSegments;
        int claims;
    }

    // What one redraw found in range, fitted in the budget or not.
    public record Counts(int reinforced, int borderSegments, int claims, int pieces) {
    }

    // One wireframe pass: a colour, and whether its lines draw through terrain.
    private static final class Layer {
        final int color;
        final boolean seeThrough;
        final Wireframe.EdgeSet candidates = new Wireframe.EdgeSet();

        Layer(int color, boolean seeThrough) {
            this.color = color;
            this.seeThrough = seeThrough;
        }
    }

    // A claim close enough to outline, with the geometry the scan needs.
    private record InRange(BlockPos center, int size, boolean snitch, long distanceSq) {
    }

    private static final Map<UUID, Scene> SCENES = new ConcurrentHashMap<>();

    // Occupancy for a layer nothing may hide: a claim's structure outlines in full even
    // when walled in.
    private static final Wireframe.Occupancy UNBURIED = (x, y, z) -> false;

    private ProtectionView() {
    }

    // --- overlay state --------------------------------------------------------------------------

    // Turns the overlay on and reports what it found, or off, returning null.
    public static Counts toggle(ServerPlayerEntity player) {
        Scene open = SCENES.remove(player.getUuid());
        if (open != null) {
            clearScene(player, open);
            return null;
        }
        Scene scene = new Scene();
        SCENES.put(player.getUuid(), scene);
        return refresh(player, scene);
    }

    // Drops a viewer without sending anything, for disconnect.
    public static void stop(UUID player) {
        SCENES.remove(player);
    }

    // Clears all viewer state, e.g. on server stop.
    public static void clear() {
        SCENES.clear();
        Displays.clear();
    }

    // --- scheduling -----------------------------------------------------------------------------

    // Marks every viewer whose scene covers this position for a redraw, and evicts the
    // cached ring measurement of any claim whose footprint holds it.
    public static void invalidate(ServerWorld world, BlockPos pos) {
        if (SCENES.isEmpty()) {
            return;
        }
        RegistryKey<World> key = world.getRegistryKey();
        int x = pos.getX();
        int z = pos.getZ();
        for (Scene scene : SCENES.values()) {
            if (scene.window == null || !key.equals(scene.dimension)) {
                continue;
            }
            // A remeasure needs a redraw to take effect, even for a footprint past the window.
            // Every block change reaches here, so the walk is fenced behind the footprint box.
            if (scene.touchesMarker(x, z) && evictMarker(scene, x, z)) {
                scene.stale = true;
            }
            if (scene.stale) {
                continue;
            }
            // The Y band the scan covers, plus the edge row its outermost blocks share.
            if (scene.window.contains(x, z)
                    && Math.abs(pos.getY() - scene.y) <= BORDER_HEIGHT + 1) {
                scene.stale = true;
            }
        }
    }

    // Drops the cached ring measurement for every claim whose footprint covers this column.
    private static boolean evictMarker(Scene scene, int x, int z) {
        boolean evicted = false;
        Iterator<Map.Entry<BlockPos, Marker>> it = scene.markers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Marker> entry = it.next();
            BlockPos center = entry.getKey();
            int size = entry.getValue().size();
            if (x >= center.getX() - size && x <= center.getX() + size
                    && z >= center.getZ() - size && z <= center.getZ() + size) {
                it.remove();
                evicted = true;
            }
        }
        return evicted;
    }

    // Redraws the viewers whose scene has gone stale, dropping any who logged out.
    public static void tick(MinecraftServer server) {
        if (SCENES.isEmpty()) {
            return;
        }
        int now = server.getTicks();
        for (Map.Entry<UUID, Scene> viewer : SCENES.entrySet()) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(viewer.getKey());
            if (player == null) {
                SCENES.remove(viewer.getKey());
                continue;
            }
            Scene scene = viewer.getValue();
            if (moved(player, scene)) {
                scene.stale = true;
            }
            if (!scene.stale || now - scene.lastDraw < MIN_REDRAW_TICKS) {
                continue;
            }
            // Skipped while the client is still working through the last redraw.
            if (!Displays.keepingUp(player)) {
                continue;
            }
            refresh(player, scene);
        }
    }

    // Whether the viewer has left the window, band or dimension the last redraw was built for.
    private static boolean moved(ServerPlayerEntity player, Scene scene) {
        return scene.window == null
                || !scene.window.equals(Window.around(player))
                || Math.abs(player.getBlockY() - scene.y) >= REDRAW_Y_STEP
                || !player.getWorld().getRegistryKey().equals(scene.dimension);
    }

    // --- redraw ---------------------------------------------------------------------------------

    private static Counts refresh(ServerPlayerEntity viewer, Scene scene) {
        // Cleared before the build, so a change landing mid-build isn't swallowed.
        scene.stale = false;
        scene.lastDraw = viewer.getServer().getTicks();

        ServerWorld world = (ServerWorld) viewer.getWorld();
        if (scene.dimension != null && !scene.dimension.equals(world.getRegistryKey())) {
            // The client drops every entity on a dimension change; nothing to destroy.
            scene.sent.clear();
            scene.markers.clear();
        }
        scene.dimension = world.getRegistryKey();
        scene.window = Window.around(viewer);
        scene.y = viewer.getBlockY();

        Build build = build(world, scene.window, viewer.getBlockPos(), viewer.getEyePos(), scene.markers);
        // After the build, which is what populates and re-measures the markers.
        scene.refreshMarkerBounds();
        Set<Piece> target = build.pieces;

        // One batch, removals first, so the client applies it all in one frame.
        List<Packet<? super ClientPlayPacketListener>> batch = new ArrayList<>();

        IntArrayList gone = new IntArrayList();
        ObjectIterator<Object2IntMap.Entry<Piece>> it = scene.sent.object2IntEntrySet().fastIterator();
        while (it.hasNext()) {
            Object2IntMap.Entry<Piece> entry = it.next();
            if (!target.contains(entry.getKey())) {
                gone.add(entry.getIntValue());
                it.remove();
            }
        }
        if (!gone.isEmpty()) {
            batch.add(Displays.destroyPacket(gone.toIntArray()));
        }

        for (Piece piece : target) {
            if (scene.sent.containsKey(piece)) {
                continue;
            }
            int id = Displays.nextId();
            boolean queued = Displays.addQuad(batch, world, id,
                    piece.x(), piece.y(), piece.z(), piece.length(), piece.thickness(), piece.flipped(),
                    piece.span(), LINE, piece.color(), piece.seeThrough());
            if (queued) {
                scene.sent.put(piece, id);
            }
        }
        Displays.sendBundled(viewer, batch);
        return new Counts(build.reinforced, build.borderSegments, build.claims, scene.sent.size());
    }

    private static void clearScene(ServerPlayerEntity viewer, Scene scene) {
        if (!scene.sent.isEmpty()) {
            Displays.sendBundled(viewer, List.of(Displays.destroyPacket(scene.sent.values().toIntArray())));
        }
        scene.sent.clear();
    }

    // --- scene ----------------------------------------------------------------------------------

    // Builds every layer, then turns each one's surviving edges into pieces.
    private static Build build(ServerWorld world, Window window, BlockPos eye, Vec3d look,
                               Map<BlockPos, Marker> markers) {
        Build build = new Build();
        Occupancy occupied = new Occupancy(world);

        Layer reinforced = new Layer(REINFORCED, false);
        Layer border = new Layer(CLAIM_BORDER, false);
        // A landmark: it draws through whatever has been built around it.
        Layer claimStructure = new Layer(CLAIM_STRUCTURE, true);

        reinforcedLayer(world, window, eye, occupied, reinforced, build);
        claimLayers(world, window, eye, occupied, border, claimStructure, build, markers);

        // Priority order: it decides what the budget keeps, and which colour a shared edge takes.
        Wireframe.EdgeSet drawn = new Wireframe.EdgeSet();
        drawLayer(build, claimStructure, drawn, look, occupied);
        drawLayer(build, reinforced, drawn, look, occupied);
        drawMarkers(build, drawn, occupied);
        build.borderSegments = drawLayer(build, border, drawn, look, occupied);
        return build;
    }

    // Every explicit reinforcement in the window and band, minus any fully buried.
    private static void reinforcedLayer(ServerWorld world, Window window, BlockPos eye,
                                        Wireframe.Occupancy occupied, Layer layer, Build build) {
        ReinforcementState store = ProtectionManager.reinforcements(world);
        if (store.isEmpty()) {
            return;
        }
        int minY = eye.getY() - BORDER_HEIGHT;
        int maxY = eye.getY() + BORDER_HEIGHT;
        Set<BlockPos> marked = new HashSet<>();
        for (int cx = window.minChunkX(); cx <= window.maxChunkX(); cx++) {
            for (int cz = window.minChunkZ(); cz <= window.maxChunkZ(); cz++) {
                LongIterator positions = store.inChunk(cx, cz).iterator();
                while (positions.hasNext()) {
                    // Only a position surviving the band and enclosure tests needs a BlockPos.
                    long packed = positions.nextLong();
                    int y = BlockPos.unpackLongY(packed);
                    if (y < minY || y > maxY) {
                        continue;
                    }
                    Reinforcement record = store.get(packed);
                    if (record == null || !record.isExplicit()) {
                        continue;
                    }
                    build.reinforced++;
                    int x = BlockPos.unpackLongX(packed);
                    int z = BlockPos.unpackLongZ(packed);
                    if (!enclosed(x, y, z, occupied)) {
                        marked.add(new BlockPos(x, y, z));
                    }
                }
            }
        }
        layer.candidates.addAll(Wireframe.candidates(marked, occupied));
    }

    // Draws one layer's edges as segments, less any an earlier layer took. Subtracts before
    // merging, and draws closest first so a layer over budget loses its farthest lines.
    private static int drawLayer(Build build, Layer layer, Wireframe.EdgeSet drawn, Vec3d look,
                                 Wireframe.Occupancy occupied) {
        layer.candidates.subtract(drawn);
        drawn.addAll(layer.candidates);
        // merge() hands back a fresh mutable list, so sort it in place.
        List<Wireframe.Segment> segments = Wireframe.merge(layer.candidates);
        segments.sort(Comparator.comparingDouble(segment -> distanceSq(segment, look)));
        for (Wireframe.Segment segment : segments) {
            line(build, segment, layer.color, layer.seeThrough, occupied);
        }
        return segments.size();
    }

    // --- claim border and structure -------------------------------------------------------------

    // Every claim in range, nearest first: its boundary planes and, once its claim block is
    // in the window, that block, its resource block and its snitch.
    private static void claimLayers(ServerWorld world, Window window, BlockPos eye,
                                    Wireframe.Occupancy occupied, Layer border, Layer claimStructure,
                                    Build build, Map<BlockPos, Marker> markers) {
        ClaimState state = ClaimManager.getState(world);
        if (state.isEmpty()) {
            return;
        }
        int minY = Math.max(world.getBottomY(), eye.getY() - BORDER_HEIGHT);
        int maxY = Math.min(world.getTopYInclusive(), eye.getY() + BORDER_HEIGHT);

        for (InRange near : inRangeClaims(state, window, eye)) {
            BlockPos center = near.center();
            int size = near.size();
            // The ring caps this claim's wall height.
            Marker marker = markers.get(center);
            if (marker == null) {
                marker = measureMarker(world, center, size);
                markers.put(center, marker);
            }
            build.markers.addAll(marker.segments());

            int minX = center.getX() - size;
            int maxX = center.getX() + size;
            int minZ = center.getZ() - size;
            int maxZ = center.getZ() + size;
            int fromZ = Math.max(minZ, window.minZ());
            int toZ = Math.min(maxZ, window.maxZ());
            int fromX = Math.max(minX, window.minX());
            int toX = Math.min(maxX, window.maxX());

            // One block below the ring puts the topmost wall edge exactly on it.
            int wallMaxY = Math.min(maxY, marker.ceiling() - 1);

            addPlane(border.candidates, Wireframe.Axis.X, minX, fromZ, toZ, minY, wallMaxY, window, occupied);
            addPlane(border.candidates, Wireframe.Axis.X, maxX + 1, fromZ, toZ, minY, wallMaxY, window, occupied);
            addPlane(border.candidates, Wireframe.Axis.Z, minZ, fromX, toX, minY, wallMaxY, window, occupied);
            addPlane(border.candidates, Wireframe.Axis.Z, maxZ + 1, fromX, toX, minY, wallMaxY, window, occupied);

            if (!window.contains(center.getX(), center.getZ())) {
                continue;
            }
            build.claims++;
            Set<BlockPos> structure = new HashSet<>();
            structure.add(center);
            structure.add(center.down());
            if (near.snitch()) {
                structure.add(center.up());
            }
            claimStructure.candidates.addAll(Wireframe.candidates(structure, UNBURIED));
        }
    }

    // One claim's landmark: a square ring over its footprint plus a drop from each corner.
    // Never clipped to the window or band, so it stays a closed square.
    private static Marker measureMarker(ServerWorld world, BlockPos center, int size) {
        int ringY = highest(world, center, size) + MARKER_CLEARANCE;
        int minX = center.getX() - size;
        int maxX = center.getX() + size + 1;
        int minZ = center.getZ() - size;
        int maxZ = center.getZ() + size + 1;
        List<Wireframe.Segment> segments = new ArrayList<>();
        segments.add(new Wireframe.Segment(Wireframe.Axis.X, minX, ringY, minZ, maxX - minX));
        segments.add(new Wireframe.Segment(Wireframe.Axis.X, minX, ringY, maxZ, maxX - minX));
        segments.add(new Wireframe.Segment(Wireframe.Axis.Z, minX, ringY, minZ, maxZ - minZ));
        segments.add(new Wireframe.Segment(Wireframe.Axis.Z, maxX, ringY, minZ, maxZ - minZ));
        addDrop(segments, world, minX, minZ, ringY, center.getY());
        addDrop(segments, world, maxX, minZ, ringY, center.getY());
        addDrop(segments, world, minX, maxZ, ringY, center.getY());
        addDrop(segments, world, maxX, maxZ, ringY, center.getY());
        return new Marker(size, ringY, segments);
    }

    // One corner drop, stopping at the ground rather than the bottom of the world.
    private static void addDrop(List<Wireframe.Segment> into, ServerWorld world, int x, int z, int ringY, int fallback) {
        int ground = Math.max(world.getBottomY(), cornerGround(world, x, z, fallback));
        if (ringY > ground) {
            into.add(new Wireframe.Segment(Wireframe.Axis.Y, x, ground, z, ringY - ground));
        }
    }

    // The surface Y under a ring corner: the lowest of the four columns meeting there.
    // Reads loaded chunks only, falling back where nothing is loaded.
    private static int cornerGround(ServerWorld world, int cornerX, int cornerZ, int fallback) {
        int lowest = Integer.MAX_VALUE;
        for (int x = cornerX - 1; x <= cornerX; x++) {
            for (int z = cornerZ - 1; z <= cornerZ; z++) {
                WorldChunk chunk = world.getChunkManager().getWorldChunk(x >> 4, z >> 4);
                if (chunk != null) {
                    lowest = Math.min(lowest, chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, x, z));
                }
            }
        }
        return lowest == Integer.MAX_VALUE ? fallback : lowest;
    }

    // The tallest block in the footprint, from loaded chunks' heightmaps, seeded with the
    // claim block's own Y.
    private static int highest(ServerWorld world, BlockPos center, int size) {
        int top = center.getY();
        int minX = center.getX() - size;
        int maxX = center.getX() + size;
        int minZ = center.getZ() - size;
        int maxZ = center.getZ() + size;
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                WorldChunk chunk = world.getChunkManager().getWorldChunk(cx, cz);
                if (chunk == null) {
                    continue;
                }
                int toX = Math.min(maxX, (cx << 4) + 15);
                int toZ = Math.min(maxZ, (cz << 4) + 15);
                for (int x = Math.max(minX, cx << 4); x <= toX; x++) {
                    for (int z = Math.max(minZ, cz << 4); z <= toZ; z++) {
                        top = Math.max(top, chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, x, z));
                    }
                }
            }
        }
        return top;
    }

    // Claims reaching the window, nearest boundary first, capped at MAX_CLAIMS.
    private static List<InRange> inRangeClaims(ClaimState state, Window window, BlockPos eye) {
        List<InRange> nearby = new ArrayList<>();
        // Through the chunk index, with an extra ring for a wall just past the window's edge.
        LongOpenHashSet seen = new LongOpenHashSet();
        for (int cx = window.minChunkX() - 1; cx <= window.maxChunkX() + 1; cx++) {
            for (int cz = window.minChunkZ() - 1; cz <= window.maxChunkZ() + 1; cz++) {
                LongIterator candidates = state.candidatesAt(cx << 4, cz << 4).iterator();
                while (candidates.hasNext()) {
                    long packed = candidates.nextLong();
                    if (!seen.add(packed)) {
                        continue;
                    }
                    ClaimState.Claim claim = state.get(packed);
                    if (claim == null) {
                        continue;
                    }
                    int size = Config.get().tierSize(claim.tier());
                    if (size < 0) {
                        continue;
                    }
                    int centerX = BlockPos.unpackLongX(packed);
                    int centerZ = BlockPos.unpackLongZ(packed);
                    int minX = centerX - size;
                    int maxX = centerX + size;
                    int minZ = centerZ - size;
                    int maxZ = centerZ + size;
                    if (!window.overlaps(minX - 1, maxX + 1, minZ - 1, maxZ + 1)) {
                        continue;
                    }
                    // Only a claim surviving the window test needs a key object.
                    nearby.add(new InRange(BlockPos.fromLong(packed), size, claim.snitch(),
                            borderDistanceSq(eye, minX, maxX, minZ, maxZ)));
                }
            }
        }
        nearby.sort(Comparator.comparingLong(InRange::distanceSq));
        return nearby.size() > MAX_CLAIMS ? nearby.subList(0, MAX_CLAIMS) : nearby;
    }

    // Squared horizontal distance from the viewer to a claim's wall, inside or out.
    private static long borderDistanceSq(BlockPos eye, int minX, int maxX, int minZ, int maxZ) {
        long dx = Math.max(Math.max(minX - eye.getX(), eye.getX() - (maxX + 1)), 0);
        long dz = Math.max(Math.max(minZ - eye.getZ(), eye.getZ() - (maxZ + 1)), 0);
        if (dx > 0 || dz > 0) {
            return dx * dx + dz * dz;
        }
        long inside = Math.min(
                Math.min(eye.getX() - minX, maxX + 1L - eye.getX()),
                Math.min(eye.getZ() - minZ, maxZ + 1L - eye.getZ()));
        return inside * inside;
    }

    // Scans one boundary plane, unless it falls outside the window.
    private static void addPlane(Wireframe.EdgeSet into, Wireframe.Axis normal, int planeCoord,
                                 int minT, int maxT, int minY, int maxY, Window window,
                                 Wireframe.Occupancy occupied) {
        if (minT > maxT || minY > maxY) {
            return;
        }
        // The wall's own coordinate must be in the window too, not just the span along it.
        boolean inWindow = normal == Wireframe.Axis.X
                ? planeCoord >= window.minX() && planeCoord <= window.maxX() + 1
                : planeCoord >= window.minZ() && planeCoord <= window.maxZ() + 1;
        if (!inWindow) {
            return;
        }
        BorderWireframe.candidates(normal, planeCoord, minT, maxT, minY, maxY, occupied, into);
    }

    // The claim markers, broken back down to edges so they go through the same dedupe:
    // a ring sits exactly on its own wall's top row, and two claims can share one.
    private static void drawMarkers(Build build, Wireframe.EdgeSet drawn, Wireframe.Occupancy occupied) {
        Wireframe.EdgeSet edges = new Wireframe.EdgeSet();
        for (Wireframe.Segment segment : build.markers) {
            Wireframe.explode(segment, edges);
        }
        edges.subtract(drawn);
        drawn.addAll(edges);
        for (Wireframe.Segment segment : Wireframe.merge(edges)) {
            line(build, segment, CLAIM_BORDER, false, occupied);
        }
    }

    // Squared distance from a point to the nearest point on a segment.
    private static double distanceSq(Wireframe.Segment segment, Vec3d point) {
        double x0 = segment.x();
        double y0 = segment.y();
        double z0 = segment.z();
        double x1 = x0;
        double y1 = y0;
        double z1 = z0;
        switch (segment.axis()) {
            case X -> x1 += segment.length();
            case Y -> y1 += segment.length();
            case Z -> z1 += segment.length();
        }
        double cx = Math.clamp(point.x, Math.min(x0, x1), Math.max(x0, x1));
        double cy = Math.clamp(point.y, Math.min(y0, y1), Math.max(y0, y1));
        double cz = Math.clamp(point.z, Math.min(z0, z1), Math.max(z0, z1));
        double dx = point.x - cx;
        double dy = point.y - cy;
        double dz = point.z - cz;
        return dx * dx + dy * dy + dz * dz;
    }

    // --- pieces ---------------------------------------------------------------------------------

    // A run as four rectangles: two crossing planes, each a back-to-back pair. All four or
    // none, since a part-faced line reads as a different shape.
    private static void line(Build build, Wireframe.Segment segment, int color, boolean seeThrough,
                             Wireframe.Occupancy occupied) {
        Wireframe.Axis axis = segment.axis();
        float span = segment.length();
        double x = segment.x() + (axis == Wireframe.Axis.X ? span / 2.0 : 0.0);
        double y = segment.y() + (axis == Wireframe.Axis.Y ? span / 2.0 : 0.0);
        double z = segment.z() + (axis == Wireframe.Axis.Z ? span / 2.0 : 0.0);

        List<Piece> group = new ArrayList<>(4);
        for (Wireframe.Axis thickness : across(axis)) {
            // A see-through line never fights the depth buffer, so it needs no bias — and
            // skipping it keeps a claim's structure a function of its own blocks alone.
            double shift = seeThrough ? 0.0 : bias(occupied, segment, axis, thickness);
            group.add(face(x, y, z, axis, thickness, false, shift, span, color, seeThrough));
            group.add(face(x, y, z, axis, thickness, true, shift, span, color, seeThrough));
        }
        if (build.pieces.size() + group.size() <= MAX_PIECES) {
            build.pieces.addAll(group);
        }
    }

    // Which way to nudge a plane sitting on the lattice: away from whichever side holds
    // more solid. Sampled at the run's midpoint.
    private static double bias(Wireframe.Occupancy occupied, Wireframe.Segment segment,
                               Wireframe.Axis axis, Wireframe.Axis thickness) {
        Wireframe.Axis normal = third(axis, thickness);
        int[] cell = {segment.x(), segment.y(), segment.z()};
        cell[axis.ordinal()] += segment.length() / 2;
        int behind = 0;
        int ahead = 0;
        // The four blocks meeting along the edge, as the pairs either side of the plane.
        for (int side = -1; side <= 0; side++) {
            int[] probe = cell.clone();
            probe[thickness.ordinal()] += side;
            probe[normal.ordinal()]--;
            if (occupied.at(probe[0], probe[1], probe[2])) {
                behind++;
            }
            probe[normal.ordinal()]++;
            if (occupied.at(probe[0], probe[1], probe[2])) {
                ahead++;
            }
        }
        return ahead > behind ? -DEPTH_BIAS : DEPTH_BIAS;
    }

    // One face of a line, turned to face along the remaining axis and shifted clear of terrain.
    private static Piece face(double x, double y, double z, Wireframe.Axis axis,
                              Wireframe.Axis thickness, boolean flipped, double shift,
                              float span, int color, boolean seeThrough) {
        Wireframe.Axis normal = third(axis, thickness);
        return new Piece(
                x + (normal == Wireframe.Axis.X ? shift : 0.0),
                y + (normal == Wireframe.Axis.Y ? shift : 0.0),
                z + (normal == Wireframe.Axis.Z ? shift : 0.0),
                axis, thickness, flipped, span, color, seeThrough);
    }

    // The two axes perpendicular to this one.
    private static Wireframe.Axis[] across(Wireframe.Axis axis) {
        return switch (axis) {
            case X -> new Wireframe.Axis[]{Wireframe.Axis.Y, Wireframe.Axis.Z};
            case Y -> new Wireframe.Axis[]{Wireframe.Axis.X, Wireframe.Axis.Z};
            case Z -> new Wireframe.Axis[]{Wireframe.Axis.X, Wireframe.Axis.Y};
        };
    }

    // The axis that is neither of these two.
    private static Wireframe.Axis third(Wireframe.Axis first, Wireframe.Axis second) {
        for (Wireframe.Axis axis : Wireframe.AXES) {
            if (axis != first && axis != second) {
                return axis;
            }
        }
        throw new IllegalArgumentException("axes must differ: " + first + ", " + second);
    }

    // --- occupancy --------------------------------------------------------------------------------

    // Solid-cube lookups for one scene build. Never loads a chunk: an unloaded position
    // reports loaded false rather than reading as air.
    private static final class Occupancy implements Wireframe.Occupancy {
        private final ServerWorld world;
        private final Long2ByteOpenHashMap cache = new Long2ByteOpenHashMap();
        private final BlockPos.Mutable probe = new BlockPos.Mutable();
        private long chunkKey = Long.MIN_VALUE;
        private WorldChunk chunk;

        Occupancy(ServerWorld world) {
            this.world = world;
            this.cache.defaultReturnValue((byte) -1);
        }

        @Override
        public boolean at(int x, int y, int z) {
            long key = BlockPos.asLong(x, y, z);
            byte cached = cache.get(key);
            if (cached >= 0) {
                return cached != 0;
            }
            boolean solid = compute(x, y, z);
            cache.put(key, (byte) (solid ? 1 : 0));
            return solid;
        }

        @Override
        public boolean probe(int x, int y, int z) {
            return compute(x, y, z);
        }

        @Override
        public boolean loaded(int x, int z) {
            return chunkAt(x >> 4, z >> 4) != null;
        }

        // Reads down to the chunk section: the boundary scan walks a whole Y column per
        // (x, z), so a section resolves once for sixteen of these.
        private boolean compute(int x, int y, int z) {
            if (world.isOutOfHeightLimit(y)) {
                return false;
            }
            WorldChunk loaded = chunkAt(x >> 4, z >> 4);
            if (loaded == null) {
                return false;
            }
            int index = loaded.getSectionIndex(y);
            ChunkSection[] sections = loaded.getSectionArray();
            if (index < 0 || index >= sections.length) {
                return false;
            }
            ChunkSection section = sections[index];
            if (section == null || section.isEmpty()) {
                return false;
            }
            BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
            if (state.isAir()) {
                return false;
            }
            probe.set(x, y, z);
            return state.isFullCube(world, probe);
        }

        // Caches the last chunk, since the scans walk in runs along one axis.
        private WorldChunk chunkAt(int chunkX, int chunkZ) {
            long key = ChunkPos.toLong(chunkX, chunkZ);
            if (key != chunkKey) {
                chunkKey = key;
                chunk = world.getChunkManager().getWorldChunk(chunkX, chunkZ);
            }
            return chunk;
        }
    }

    // --- helpers --------------------------------------------------------------------------------

    // Six solid neighbours: nothing of this block is visible, so it needs no outline.
    private static boolean enclosed(int x, int y, int z, Wireframe.Occupancy occupied) {
        return occupied.at(x - 1, y, z) && occupied.at(x + 1, y, z)
                && occupied.at(x, y - 1, z) && occupied.at(x, y + 1, z)
                && occupied.at(x, y, z - 1) && occupied.at(x, y, z + 1);
    }

}
