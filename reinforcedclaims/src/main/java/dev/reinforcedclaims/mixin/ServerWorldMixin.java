package dev.reinforcedclaims.mixin;

import dev.reinforcedclaims.util.ProtectionView;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Feeds block changes to the /clm view overlay so it can redraw.
@Mixin(ServerWorld.class)
public abstract class ServerWorldMixin {

    @Inject(method = "updateListeners", at = @At("TAIL"))
    private void reinforcedclaims$scheduleOverlayRedraw(BlockPos pos, BlockState oldState, BlockState newState,
                                                        int flags, CallbackInfo ci) {
        ProtectionView.invalidate((ServerWorld) (Object) this, pos);
    }
}
