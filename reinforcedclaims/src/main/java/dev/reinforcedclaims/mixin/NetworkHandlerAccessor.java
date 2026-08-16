package dev.reinforcedclaims.mixin;

import net.minecraft.network.ClientConnection;
import net.minecraft.server.network.ServerCommonNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Exposes a player's connection, for the overlay's send throttling.
@Mixin(ServerCommonNetworkHandler.class)
public interface NetworkHandlerAccessor {

    @Accessor("connection")
    ClientConnection reinforcedclaims$connection();
}
