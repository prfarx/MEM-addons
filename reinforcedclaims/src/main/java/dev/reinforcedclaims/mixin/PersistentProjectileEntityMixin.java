package dev.reinforcedclaims.mixin;

import dev.reinforcedclaims.claim.ClaimManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.SpectralArrowEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Lets a player's arrow chip a protected block's HP, then removes the arrow.
@Mixin(PersistentProjectileEntity.class)
public abstract class PersistentProjectileEntityMixin {

    @Inject(method = "onBlockHit", at = @At("HEAD"), cancellable = true)
    private void reinforcedclaims$damageProtected(BlockHitResult blockHitResult, CallbackInfo ci) {
        PersistentProjectileEntity self = (PersistentProjectileEntity) (Object) this;
        // Arrows only, not other projectiles sharing this base class.
        if (!(self instanceof ArrowEntity) && !(self instanceof SpectralArrowEntity)) {
            return;
        }
        if (!(self.getWorld() instanceof ServerWorld world)) {
            return;
        }
        PlayerEntity shooter = self.getOwner() instanceof PlayerEntity p ? p : null;
        BlockPos pos = blockHitResult.getBlockPos();
        if (ClaimManager.onProjectileHit(world, pos, shooter)) {
            self.discard();
            ci.cancel();
        }
    }
}
