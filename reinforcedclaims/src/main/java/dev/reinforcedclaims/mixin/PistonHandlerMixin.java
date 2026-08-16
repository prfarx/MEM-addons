package dev.reinforcedclaims.mixin;

import dev.reinforcedclaims.claim.ClaimManager;
import dev.reinforcedclaims.claim.ClaimState;
import dev.reinforcedclaims.protection.ProtectionManager;
import dev.reinforcedclaims.reinforcement.ReinforcementState;
import net.minecraft.block.piston.PistonHandler;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

// Refuses a push that crosses a claim edge or moves a reinforced block.
@Mixin(PistonHandler.class)
public abstract class PistonHandlerMixin {

    @Shadow @Final private World world;

    @Shadow public abstract Direction getMotionDirection();

    @Shadow public abstract List<BlockPos> getMovedBlocks();

    @Shadow public abstract List<BlockPos> getBrokenBlocks();

    @Inject(method = "calculatePush", at = @At("RETURN"), cancellable = true)
    private void reinforcedclaims$refuseProtectedPush(CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() || !(this.world instanceof ServerWorld serverWorld)) {
            return;
        }
        Direction dir = getMotionDirection();
        ClaimState claims = ClaimManager.getState(serverWorld);
        ReinforcementState reinforcements = ProtectionManager.reinforcements(serverWorld);
        for (BlockPos from : getMovedBlocks()) {
            if (ClaimManager.isExplicitlyReinforced(reinforcements, from)
                    || ClaimManager.crossesClaimBoundary(claims, from, from.offset(dir))) {
                cir.setReturnValue(false);
                return;
            }
        }
        // Blocks the push destroys rather than moves.
        for (BlockPos broken : getBrokenBlocks()) {
            if (ClaimManager.isExplicitlyReinforced(reinforcements, broken)
                    || ClaimManager.crossesClaimBoundary(claims, broken.offset(dir.getOpposite()), broken)) {
                cir.setReturnValue(false);
                return;
            }
        }
    }
}
