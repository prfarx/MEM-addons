package dev.reinforcedclaims.mixin;

import dev.reinforcedclaims.claim.ClaimManager;
import net.minecraft.block.BlockState;
import net.minecraft.block.DispenserBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Cancels a dispenser firing across a claim boundary.
@Mixin(DispenserBlock.class)
public abstract class DispenserBlockMixin {

    @Inject(method = "dispense", at = @At("HEAD"), cancellable = true)
    private void reinforcedclaims$blockDispenseIntoTerritory(ServerWorld world, BlockState state, BlockPos pos, CallbackInfo ci) {
        Direction facing = state.get(DispenserBlock.FACING);
        BlockPos front = pos.offset(facing);
        if (ClaimManager.crossesIntoClaim(ClaimManager.getState(world), pos, front)) {
            ci.cancel();
        }
    }
}
