package dev.reinforcedclaims.claim;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.reinforcedclaims.util.Enums;

// One snitch entry/exit line.
public record LogEntry(long time, Type type, String player) {

    public enum Type {
        ENTER, EXIT
    }

    // Lenient: an unreadable type reads as EXIT rather than failing the decode.
    private static final Codec<Type> TYPE_CODEC = Enums.lenientCodec(Type.values(), Type.EXIT);

    public static final Codec<LogEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.LONG.fieldOf("time").forGetter(LogEntry::time),
            TYPE_CODEC.fieldOf("type").forGetter(LogEntry::type),
            Codec.STRING.fieldOf("player").forGetter(LogEntry::player)
    ).apply(instance, LogEntry::new));

    public static LogEntry now(Type type, String player) {
        return new LogEntry(System.currentTimeMillis(), type, player);
    }
}
