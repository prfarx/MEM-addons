package dev.reinforcedclaims.mixin.middleearth;

import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import dev.reinforcedclaims.compat.MiddleEarthMultiBlocks;
import dev.reinforcedclaims.protection.ProtectionManager;
import dev.reinforcedclaims.reinforcement.Reinforcement;
import dev.reinforcedclaims.reinforcement.ReinforcementState;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.sevenstars.middleearth.block.special.LargeDoorBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

// Moves a large door's reinforcement records when it swings to a new layout.
@Mixin(LargeDoorBlock.class)
public abstract class LargeDoorBlockMixin {

    @Inject(method = "onUse", at = @At("HEAD"))
    private void reinforcedclaims$rememberDoor(BlockState state, World world, BlockPos pos, PlayerEntity player,
                                               BlockHitResult hit, CallbackInfoReturnable<ActionResult> cir,
                                               @Share("before") LocalRef<BlockState> before) {
        if (!world.isClient() && ProtectionManager.reinforcements((ServerWorld) world).get(pos) != null) {
            before.set(state);
        }
    }

    @Inject(method = "onUse", at = @At("RETURN"))
    private void reinforcedclaims$followDoor(BlockState state, World world, BlockPos pos, PlayerEntity player,
                                             BlockHitResult hit, CallbackInfoReturnable<ActionResult> cir,
                                             @Share("before") LocalRef<BlockState> before) {
        BlockState previous = before.get();
        if (previous == null) {
            return;
        }
        LargeDoorBlock door = (LargeDoorBlock) (Object) this;
        BlockPos origin = MiddleEarthMultiBlocks.doorOrigin(pos, previous, door);
        List<BlockPos> was = MiddleEarthMultiBlocks.doorParts(origin, previous, door);
        BlockState now = world.getBlockState(origin);
        // No door at the origin means it's gone; the records go too.
        List<BlockPos> is = now.isOf(door) ? MiddleEarthMultiBlocks.doorParts(origin, now, door) : List.of();
        if (was.equals(is)) {
            return;
        }
        ReinforcementState store = ProtectionManager.reinforcements((ServerWorld) world);
        Reinforcement record = null;
        for (BlockPos part : was) {
            Reinforcement found = store.remove(part);
            record = record != null ? record : found;
        }
        if (record != null) {
            for (BlockPos part : is) {
                store.put(part, record);
            }
        }
        store.markDirty();
    }
}
