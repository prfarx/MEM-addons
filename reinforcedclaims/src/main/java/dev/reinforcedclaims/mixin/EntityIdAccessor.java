package dev.reinforcedclaims.mixin;

import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.atomic.AtomicInteger;

// Exposes the entity network id counter, for the overlay's fake entities.
@Mixin(Entity.class)
public interface EntityIdAccessor {

    @Accessor("CURRENT_ID")
    static AtomicInteger reinforcedclaims$currentId() {
        throw new AssertionError("mixin not applied");
    }
}
