package dev.waystones.waystone;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.Identifier;
import net.minecraft.util.Uuids;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.List;
import java.util.UUID;

// A placed waystone: identity, position, and the display entities it spawned.
// named = false means the name is a placeholder, not yet chosen by an op.
public record Waystone(String name, boolean named, String skin, Identifier dimension, BlockPos pos, Direction facing,
                        List<UUID> displayEntities) {

    public static final Codec<Waystone> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("name").forGetter(Waystone::name),
            Codec.BOOL.fieldOf("named").forGetter(Waystone::named),
            Codec.STRING.fieldOf("skin").forGetter(Waystone::skin),
            Identifier.CODEC.fieldOf("dimension").forGetter(Waystone::dimension),
            BlockPos.CODEC.fieldOf("pos").forGetter(Waystone::pos),
            Direction.CODEC.fieldOf("facing").forGetter(Waystone::facing),
            Uuids.CODEC.listOf().fieldOf("displayEntities").forGetter(Waystone::displayEntities)
    ).apply(instance, Waystone::new));

    // Same waystone under a new name; the caller removes the old key.
    public Waystone renamed(String newName) {
        return new Waystone(newName, true, skin, dimension, pos, facing, displayEntities);
    }
}
