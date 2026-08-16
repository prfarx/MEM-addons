package dev.reinforcedclaims.util;

import dev.reinforcedclaims.compat.MiddleEarthMultiBlocks;
import dev.reinforcedclaims.config.Config;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// The positions one multi-block object occupies, from vanilla half/part states,
// Middle-earth's own layouts, or the config's multiBlocks groups.
public final class MultiBlock {

    private static final String MIDDLE_EARTH_ID = "middle-earth";

    // Checked before touching MiddleEarthMultiBlocks, whose types are absent at runtime without it.
    private static final boolean MIDDLE_EARTH = FabricLoader.getInstance().isModLoaded(MIDDLE_EARTH_ID);

    private MultiBlock() {
    }

    // Every position in the same object as pos, pos included. An ordinary block is a group of one.
    public static List<BlockPos> parts(World world, BlockPos pos) {
        BlockPos origin = pos.toImmutable();
        BlockState state = world.getBlockState(origin);
        if (state.isAir()) {
            return List.of(origin);
        }
        BlockPos partner = vanillaPartner(world, origin, state);
        if (partner != null) {
            return List.of(origin, partner);
        }
        if (MIDDLE_EARTH) {
            List<BlockPos> known = MiddleEarthMultiBlocks.parts(world, origin, state);
            if (known != null) {
                return known;
            }
        }
        Set<Block> group = Config.get().multiBlockGroup(state.getBlock());
        return group == null ? List.of(origin) : flood(world, origin, group);
    }

    // The other half of a two-part vanilla object, or null. Only the neighbour it extends into counts.
    private static BlockPos vanillaPartner(World world, BlockPos pos, BlockState state) {
        if (state.contains(Properties.DOUBLE_BLOCK_HALF)) {
            boolean upper = state.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER;
            return opposite(world, state, upper ? pos.down() : pos.up(), Properties.DOUBLE_BLOCK_HALF);
        }
        if (state.contains(Properties.BED_PART) && state.contains(Properties.HORIZONTAL_FACING)) {
            Direction facing = state.get(Properties.HORIZONTAL_FACING);
            boolean head = state.get(Properties.BED_PART) == BedPart.HEAD;
            return opposite(world, state, pos.offset(head ? facing.getOpposite() : facing), Properties.BED_PART);
        }
        return null;
    }

    // other, if it holds the same block carrying the opposite value of property.
    private static <T extends Comparable<T>> BlockPos opposite(World world, BlockState state, BlockPos other,
                                                               Property<T> property) {
        BlockState found = world.getBlockState(other);
        return found.isOf(state.getBlock()) && found.contains(property)
                && !found.get(property).equals(state.get(property)) ? other : null;
    }

    // Face-connected blocks from one configured group, capped so a common block can't walk the map.
    private static List<BlockPos> flood(World world, BlockPos origin, Set<Block> group) {
        int cap = Config.get().multiBlockCap();
        List<BlockPos> found = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        seen.add(origin);
        queue.add(origin);
        while (!queue.isEmpty() && found.size() < cap) {
            BlockPos next = queue.poll();
            found.add(next);
            for (Direction direction : Direction.values()) {
                BlockPos neighbour = next.offset(direction);
                if (seen.add(neighbour) && group.contains(world.getBlockState(neighbour).getBlock())) {
                    queue.add(neighbour);
                }
            }
        }
        return found;
    }
}
