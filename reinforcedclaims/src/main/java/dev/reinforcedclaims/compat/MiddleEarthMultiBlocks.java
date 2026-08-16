package dev.reinforcedclaims.compat;

import net.minecraft.block.BlockState;
import net.minecraft.block.enums.DoorHinge;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.sevenstars.middleearth.block.special.LargeDoorBlock;
import net.sevenstars.middleearth.block.special.artisantable.ArtisanTable;
import net.sevenstars.middleearth.block.special.artisantable.ArtisanTablePart;
import net.sevenstars.middleearth.block.special.forge.ForgeBlock;
import net.sevenstars.middleearth.block.special.forge.ForgePart;

import java.util.ArrayList;
import java.util.List;

// Part layouts for Middle-earth's large doors, artisan tables and forges.
// Compile-only dependency: callers check the mod is installed first.
public final class MiddleEarthMultiBlocks {

    private MiddleEarthMultiBlocks() {
    }

    // The positions of the object at pos, or null if it isn't one of these blocks.
    public static List<BlockPos> parts(World world, BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof LargeDoorBlock door) {
            return doorParts(doorOrigin(pos, state, door), state, door);
        }
        if (state.getBlock() instanceof ArtisanTable) {
            Direction facing = state.get(Properties.HORIZONTAL_FACING);
            boolean left = state.get(ArtisanTable.PART) == ArtisanTablePart.LEFT;
            return pair(world, pos, state,
                    pos.offset(left ? facing.rotateYClockwise() : facing.rotateYCounterclockwise()));
        }
        if (state.getBlock() instanceof ForgeBlock) {
            boolean bottom = state.get(ForgeBlock.PART) == ForgePart.BOTTOM;
            return pair(world, pos, state, bottom ? pos.up() : pos.down());
        }
        return null;
    }

    // Both halves, or just pos when the other isn't there.
    private static List<BlockPos> pair(World world, BlockPos pos, BlockState state, BlockPos other) {
        return world.getBlockState(other).isOf(state.getBlock()) ? List.of(pos, other) : List.of(pos);
    }

    // The axis a large door's columns run along.
    public static Direction doorWidthDirection(BlockState state) {
        Direction facing = state.get(LargeDoorBlock.HORIZONTAL_FACING);
        if (state.get(LargeDoorBlock.OPEN)) {
            return facing;
        }
        return state.get(LargeDoorBlock.HINGE) == DoorHinge.LEFT
                ? facing.rotateYClockwise() : facing.rotateYCounterclockwise();
    }

    // The corner the door's layout hangs off.
    public static BlockPos doorOrigin(BlockPos pos, BlockState state, LargeDoorBlock door) {
        int part = state.get(door.getPart());
        return pos.offset(doorWidthDirection(state).getOpposite(), part / door.getDoorHeight())
                .down(part % door.getDoorHeight());
    }

    // Every position the door occupies, column by column from the bottom.
    public static List<BlockPos> doorParts(BlockPos origin, BlockState state, LargeDoorBlock door) {
        Direction width = doorWidthDirection(state);
        int height = door.getDoorHeight();
        List<BlockPos> parts = new ArrayList<>(height * door.getDoorWidth());
        for (int column = 0; column < door.getDoorWidth(); column++) {
            BlockPos bottom = origin.offset(width, column);
            for (int row = 0; row < height; row++) {
                parts.add(bottom.up(row));
            }
        }
        return parts;
    }
}
