package dev.reinforcedclaims.util;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

// The block a player is looking at.
public final class Raycast {

    // How far a command can target a block.
    public static final double REACH = 6.0;

    private Raycast() {
    }

    // The block the player is looking at, or null if nothing is in reach.
    public static BlockPos lookedAtBlock(ServerPlayerEntity player) {
        HitResult hit = player.raycast(REACH, 1.0f, false);
        if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) {
            return block.getBlockPos();
        }
        return null;
    }
}
