package dev.reinforcedclaims.util;

import com.mojang.serialization.Codec;

// Lenient enum lookups for the persisted enums: an unknown name never fails a decode.
public final class Enums {

    private Enums() {
    }

    // The named constant, or fallback. Pass a cached values() array; values() clones on every call.
    public static <E extends Enum<E>> E byName(E[] values, String name, E fallback) {
        for (E value : values) {
            if (value.name().equals(name)) {
                return value;
            }
        }
        return fallback;
    }

    // A string codec that falls back rather than failing on an unknown name.
    public static <E extends Enum<E>> Codec<E> lenientCodec(E[] values, E fallback) {
        return Codec.STRING.xmap(name -> byName(values, name, fallback), Enum::name);
    }
}
