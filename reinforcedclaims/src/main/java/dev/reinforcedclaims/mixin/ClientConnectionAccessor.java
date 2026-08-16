package dev.reinforcedclaims.mixin;

import io.netty.channel.Channel;
import net.minecraft.network.ClientConnection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Exposes the Netty channel, so the overlay can check how much is queued unsent.
@Mixin(ClientConnection.class)
public interface ClientConnectionAccessor {

    @Accessor("channel")
    Channel reinforcedclaims$channel();
}
