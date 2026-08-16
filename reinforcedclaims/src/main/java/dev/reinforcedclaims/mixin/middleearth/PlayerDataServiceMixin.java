package dev.reinforcedclaims.mixin.middleearth;

import dev.reinforcedclaims.fellowship.FactionSync;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import net.sevenstars.middleearth.resources.persistent_datas.PlayerDataService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Fires FactionSync when a player's Middle-earth faction is written or cleared.
@Mixin(PlayerDataService.class)
public class PlayerDataServiceMixin {

    @Inject(method = "clearPlayerData", at = @At("TAIL"))
    private static void reinforcedclaims$onFactionCleared(PlayerEntity player, CallbackInfoReturnable<Boolean> cir) {
        FactionSync.onFactionChanged(player);
    }

    @Inject(
            method = "setNewFactionInformation(Lnet/minecraft/entity/player/PlayerEntity;Lnet/minecraft/world/World;"
                    + "Lnet/minecraft/util/Identifier;Lnet/minecraft/util/Identifier;)Z",
            at = @At("TAIL")
    )
    private static void reinforcedclaims$onFactionSet(PlayerEntity player, World world, Identifier factionId,
                                                     Identifier spawnId, CallbackInfoReturnable<Boolean> cir) {
        FactionSync.onFactionChanged(player);
    }
}
