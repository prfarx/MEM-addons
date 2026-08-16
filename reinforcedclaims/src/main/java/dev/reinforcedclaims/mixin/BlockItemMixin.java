package dev.reinforcedclaims.mixin;

import dev.reinforcedclaims.claim.ClaimManager;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Player block placement: anti-pillar and reinforce gates, then claim creation.
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {

    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void reinforcedclaims$preventPillar(ItemPlacementContext context, CallbackInfoReturnable<ActionResult> cir) {
        if (context.getWorld().isClient()) {
            return;
        }
        PlayerEntity player = context.getPlayer();
        if (player == null) {
            return;
        }
        ServerWorld world = (ServerWorld) context.getWorld();
        BlockPos pos = context.getBlockPos();
        // Before placement, so a blocked reinforcement keeps its material.
        if (ClaimManager.handleEnemyPlacement(world, pos, player)
                || ClaimManager.handleBlockedReinforcedPlacement(world, pos, player, context.getHand())) {
            cir.setReturnValue(ActionResult.FAIL);
            player.playerScreenHandler.syncState();
        }
    }

    @Inject(method = "place", at = @At("RETURN"))
    private void reinforcedclaims$afterPlace(ItemPlacementContext context, CallbackInfoReturnable<ActionResult> cir) {
        if (context.getWorld().isClient() || !cir.getReturnValue().isAccepted()) {
            return;
        }
        if (!(context.getPlayer() instanceof ServerPlayerEntity serverPlayer)) {
            return;
        }
        ServerWorld world = (ServerWorld) context.getWorld();
        BlockPos pos = context.getBlockPos();
        BlockState placed = world.getBlockState(pos);
        ClaimManager.onBlockPlaced(world, pos, placed, serverPlayer, context.getHand());
    }
}
