package dev.reinforcedclaims.mixin;

import dev.reinforcedclaims.protection.ProtectionManager;
import dev.reinforcedclaims.reinforcement.ReinforcementState;
import dev.reinforcedclaims.claim.ClaimManager;
import dev.reinforcedclaims.claim.ClaimState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.explosion.ExplosionImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

// Removes protected blocks from an explosion's destroy list.
@Mixin(ExplosionImpl.class)
public abstract class ExplosionImplMixin {

    @Inject(method = "destroyBlocks", at = @At("HEAD"))
    private void reinforcedclaims$protectClaims(List<BlockPos> positions, CallbackInfo ci) {
        if (positions.isEmpty()) {
            return;
        }
        ExplosionImpl self = (ExplosionImpl) (Object) this;
        ServerWorld world = self.getWorld();
        ClaimState claims = ClaimManager.getState(world);
        ReinforcementState reinforcements = ProtectionManager.reinforcements(world);
        positions.removeIf(pos -> ClaimManager.isExplosionProtected(claims, reinforcements, pos));
    }
}
