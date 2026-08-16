package dev.reinforcedclaims.fellowship;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.Uuids;

import java.util.UUID;

// A pending invite: the fellowship's id, its name at send time, who sent it, and when.
public record FellowshipInvite(String key, String fellowship, UUID inviter, String inviterName, long sent) {

    public static final Codec<FellowshipInvite> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("key").forGetter(FellowshipInvite::key),
            Codec.STRING.fieldOf("fellowship").forGetter(FellowshipInvite::fellowship),
            Uuids.CODEC.fieldOf("inviter").forGetter(FellowshipInvite::inviter),
            Codec.STRING.fieldOf("inviterName").forGetter(FellowshipInvite::inviterName),
            Codec.LONG.fieldOf("sent").forGetter(FellowshipInvite::sent)
    ).apply(instance, FellowshipInvite::new));
}
