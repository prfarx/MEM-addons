package dev.reinforcedclaims.protection;

import net.minecraft.block.AbstractRedstoneGateBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ButtonBlock;
import net.minecraft.block.DaylightDetectorBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.EnderChestBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.LeverBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.inventory.Inventory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

// Sorts a right-clicked block into an InteractionType category.
public final class InteractionClassifier {

    private InteractionClassifier() {
    }

    public static InteractionType classify(World world, BlockPos pos, BlockState state) {
        Block block = state.getBlock();
        if (block instanceof EnderChestBlock) {
            return InteractionType.ENDERCHEST;
        }
        if (block instanceof DoorBlock || block instanceof TrapdoorBlock || block instanceof FenceGateBlock) {
            return InteractionType.DOOR;
        }
        if (block instanceof ButtonBlock || block instanceof LeverBlock
                || block instanceof AbstractRedstoneGateBlock || block instanceof DaylightDetectorBlock) {
            return InteractionType.REDSTONE;
        }
        // Gated on the cached flag: getBlockEntity is a chunk lookup on every right-click.
        if (state.hasBlockEntity() && world.getBlockEntity(pos) instanceof Inventory) {
            return InteractionType.CONTAINER;
        }
        return InteractionType.OTHER_INTERACT;
    }
}
