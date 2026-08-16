package dev.waystones.waystone;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtFloat;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// Builds and removes a waystone's barrier column and its display entities.
public final class WaystoneStructure {

    public static final String PART_TAG = "waystones:part";

    // Barrier blocks stacked up from the stored base position.
    public static final int HEIGHT = 3;

    private static final float BLOCK_SCALE = 1f;
    private static final float RUNE_TEXT_RADIUS = 0.625f;
    private static final float RUNE_CORE_Y = 1.5f;

    private WaystoneStructure() {
    }

    // Places the barriers and display entities; returns the spawned entity ids.
    public static List<UUID> place(ServerWorld world, BlockPos pos, WaystoneSkin skin) {
        for (int y = 0; y < HEIGHT; y++) {
            world.setBlockState(pos.up(y), Blocks.BARRIER.getDefaultState());
        }

        List<UUID> ids = new ArrayList<>();

        // block_display keeps the real block shape rather than an item icon model.
        BlockState wallState = Block.getBlockFromItem(skin.wallItem()).getDefaultState();
        addPart(ids, spawnBlockDisplay(world, pos, wallState, BLOCK_SCALE, 0.1251f, 0.5f, 0.1251f));
        addPart(ids, spawnBlockDisplay(world, pos, wallState, BLOCK_SCALE, -0.125f, 0.5f, 0.125f));
        addPart(ids, spawnBlockDisplay(world, pos, wallState, BLOCK_SCALE, -0.1251f, 0.5f, -0.1251f));
        addPart(ids, spawnBlockDisplay(world, pos, wallState, BLOCK_SCALE, 0.125f, 0.5f, -0.125f));

        // Rune core: base block wrapped by rune text on all 4 sides.
        BlockState baseState = Block.getBlockFromItem(skin.baseItem()).getDefaultState();
        addPart(ids, spawnBlockDisplay(world, pos, baseState, BLOCK_SCALE, 0.0f, -0.25f, 0.0f));
        addPart(ids, spawnBlockDisplay(world, pos, baseState, BLOCK_SCALE, 0.0f, RUNE_CORE_Y, 0.0f));
        addPart(ids, spawnBlockDisplay(world, pos, baseState, BLOCK_SCALE, 0.0f, RUNE_CORE_Y + 0.5f, 0.0f));
        ids.addAll(spawnRuneBand(world, pos, skin));

        BlockState slabState = Block.getBlockFromItem(skin.slabItem()).getDefaultState();
        float[][] cornerSlabs = {
                {0f, 1.1251f, 0.125f, 0.0001f},
                {90f, 0.125f, -0.1251f, 0.0f},
                {180f, -0.1251f, 0.875f, 0.0001f},
                {270f, 0.875f, 1.1251f, 0.0f},
        };
        for (float[] slab : cornerSlabs) {
            NbtList rotation = yThenZRotation(slab[0], 90f);
            addPart(ids, spawnBlockDisplay(world, pos, slabState, BLOCK_SCALE, slab[1], RUNE_CORE_Y + 0.25f + slab[3], slab[2], rotation));
        }

        return ids;
    }

    // Records a spawned part, skipping nulls the persisted codec would reject.
    private static void addPart(List<UUID> ids, UUID id) {
        if (id != null) {
            ids.add(id);
        }
    }

    // The 4 rune-text faces around the rune core.
    private static List<UUID> spawnRuneBand(ServerWorld world, BlockPos pos, WaystoneSkin skin) {
        List<UUID> ids = new ArrayList<>();
        // {yaw degrees, offset x, offset z}
        float[][] faces = {
                {180f, 0f, -RUNE_TEXT_RADIUS},   // north
                {90f, RUNE_TEXT_RADIUS, 0f},   // east
                {0f, 0f, RUNE_TEXT_RADIUS},  // south
                {270f, -RUNE_TEXT_RADIUS, 0f}, // west
        };
        for (float[] face : faces) {
            NbtList rotation = yAxisRotation(face[0]);
            addPart(ids, spawnTextDisplay(world, pos, skin.runeText, skin.textColor, BLOCK_SCALE * 2.5f,
                    0.5f + face[1], RUNE_CORE_Y + 0.5f, 0.5f + face[2], rotation));
        }
        return ids;
    }

    // Discards the display entities and clears the barriers.
    public static void remove(ServerWorld world, BlockPos pos, List<UUID> ids) {
        for (UUID id : ids) {
            Entity entity = world.getEntity(id);
            if (entity != null) {
                entity.discard();
            }
        }
        for (int y = 0; y < HEIGHT; y++) {
            BlockPos barrier = pos.up(y);
            if (world.getBlockState(barrier).isOf(Blocks.BARRIER)) {
                world.setBlockState(barrier, Blocks.AIR.getDefaultState());
            }
        }
    }

    private static UUID spawnBlockDisplay(ServerWorld world, BlockPos pos, BlockState state,
                                           float scale, float tx, float ty, float tz) {
        return spawnBlockDisplay(world, pos, state, scale, tx, ty, tz, identityQuaternion());
    }

    private static UUID spawnBlockDisplay(ServerWorld world, BlockPos pos, BlockState state,
                                           float scale, float tx, float ty, float tz, NbtList leftRotation) {
        NbtCompound nbt = baseNbt("minecraft:block_display", scale, tx, ty, tz, leftRotation);
        nbt.put("block_state", NbtHelper.fromBlockState(state));
        return spawnEntity(world, pos, nbt);
    }

    private static UUID spawnTextDisplay(ServerWorld world, BlockPos pos, String text, int color,
                                          float scale, float tx, float ty, float tz, NbtList leftRotation) {
        NbtCompound nbt = baseNbt("minecraft:text_display", scale, tx, ty, tz, leftRotation);
        // "text" must be an NBT compound; a JSON string would render verbatim.
        NbtCompound textComponent = new NbtCompound();
        textComponent.putString("text", text == null ? "" : text);
        textComponent.putString("color", "#" + String.format("%06X", color));
        nbt.put("text", textComponent);
        nbt.putString("billboard", "fixed");
        nbt.putInt("background", 0);
        return spawnEntity(world, pos, nbt);
    }

    // Shared id/transformation/Tags scaffolding for every display part.
    private static NbtCompound baseNbt(String entityId, float scale, float tx, float ty, float tz, NbtList leftRotation) {
        NbtCompound nbt = new NbtCompound();
        nbt.putString("id", entityId);
        NbtCompound transformation = new NbtCompound();
        transformation.put("translation", floatList(tx, ty, tz));
        transformation.put("scale", floatList(scale, scale, scale));
        transformation.put("left_rotation", leftRotation);
        transformation.put("right_rotation", identityQuaternion());
        nbt.put("transformation", transformation);
        NbtList tags = new NbtList();
        tags.add(NbtString.of(PART_TAG));
        nbt.put("Tags", tags);
        return nbt;
    }

    // Null when the entity type id doesn't resolve.
    private static UUID spawnEntity(ServerWorld world, BlockPos pos, NbtCompound nbt) {
        Entity entity = EntityType.loadEntityWithPassengers(nbt, world, SpawnReason.TRIGGERED, e -> {
            e.refreshPositionAndAngles(pos.getX(), pos.getY(), pos.getZ(), 0f, 0f);
            world.spawnEntity(e);
            return e;
        });
        return entity == null ? null : entity.getUuid();
    }

    private static NbtList floatList(float a, float b, float c) {
        NbtList list = new NbtList();
        list.add(NbtFloat.of(a));
        list.add(NbtFloat.of(b));
        list.add(NbtFloat.of(c));
        return list;
    }

    private static NbtList identityQuaternion() {
        return floatList4(0f, 0f, 0f, 1f);
    }

    // Quaternion rotating around the Y axis.
    private static NbtList yAxisRotation(float degrees) {
        double half = Math.toRadians(degrees) / 2.0;
        return floatList4(0f, (float) Math.sin(half), 0f, (float) Math.cos(half));
    }

    // Quaternion rolling around Z, then yawing around Y.
    private static NbtList yThenZRotation(float yawDegrees, float zDegrees) {
        double hy = Math.toRadians(yawDegrees) / 2.0;
        double hz = Math.toRadians(zDegrees) / 2.0;
        float sy = (float) Math.sin(hy), cy = (float) Math.cos(hy);
        float sz = (float) Math.sin(hz), cz = (float) Math.cos(hz);
        return floatList4(sy * sz, sy * cz, cy * sz, cy * cz);
    }

    private static NbtList floatList4(float a, float b, float c, float d) {
        NbtList list = new NbtList();
        list.add(NbtFloat.of(a));
        list.add(NbtFloat.of(b));
        list.add(NbtFloat.of(c));
        list.add(NbtFloat.of(d));
        return list;
    }
}
