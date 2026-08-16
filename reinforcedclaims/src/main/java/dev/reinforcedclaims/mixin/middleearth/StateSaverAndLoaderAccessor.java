package dev.reinforcedclaims.mixin.middleearth;

import net.sevenstars.middleearth.resources.StateSaverAndLoader;
import net.sevenstars.middleearth.resources.persistent_datas.PlayerData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.HashMap;
import java.util.UUID;

// Exposes Middle-earth's UUID -> player data map, so offline players resolve. Read-only.
@Mixin(StateSaverAndLoader.class)
public interface StateSaverAndLoaderAccessor {

    @Accessor("players")
    HashMap<UUID, PlayerData> reinforcedclaims$players();
}
