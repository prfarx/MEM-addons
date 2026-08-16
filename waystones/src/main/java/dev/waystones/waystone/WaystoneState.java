package dev.waystones.waystone;

import com.mojang.serialization.Codec;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateType;
import net.minecraft.world.World;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

// Server-wide store of every waystone, keyed by lowercased name.
// Mutate only through put/remove so the position index stays correct.
public class WaystoneState extends PersistentState {

    private final Map<String, Waystone> byName = new LinkedHashMap<>();

    // (dimension, pos) -> name, for every position a structure occupies. Not serialized.
    private final Map<PosKey, String> byPos = new HashMap<>();

    public WaystoneState() {
    }

    private record PosKey(Identifier dimension, BlockPos pos) {
    }

    // Every barrier position the waystone occupies.
    private static void forEachOccupied(Waystone waystone, Consumer<PosKey> action) {
        BlockPos base = waystone.pos();
        for (int y = 0; y < WaystoneStructure.HEIGHT; y++) {
            action.accept(new PosKey(waystone.dimension(), base.up(y)));
        }
    }

    // The store, pinned to the overworld save.
    public static WaystoneState get(MinecraftServer server) {
        return server.getWorld(World.OVERWORLD).getPersistentStateManager().getOrCreate(TYPE);
    }

    // --- accessors ------------------------------------------------------------------------------

    public Waystone get(String name) {
        return name == null ? null : byName.get(name.toLowerCase(Locale.ROOT));
    }

    public boolean exists(String name) {
        return get(name) != null;
    }

    public boolean isEmpty() {
        return byName.isEmpty();
    }

    // The waystone occupying this position, base block or not.
    public Waystone at(Identifier dimension, BlockPos pos) {
        if (byPos.isEmpty()) {
            return null;
        }
        String name = byPos.get(new PosKey(dimension, pos.toImmutable()));
        return name == null ? null : byName.get(name);
    }

    // Read-only view.
    public Collection<Waystone> all() {
        return Collections.unmodifiableCollection(byName.values());
    }

    // --- mutators -------------------------------------------------------------------------------

    // Adds or replaces a waystone by name, reindexing its positions.
    public void put(Waystone waystone) {
        String key = waystone.name().toLowerCase(Locale.ROOT);
        Waystone old = byName.put(key, waystone);
        if (old != null) {
            forEachOccupied(old, byPos::remove);
        }
        forEachOccupied(waystone, occupied -> byPos.put(occupied, key));
        markDirty();
    }

    public Waystone remove(String name) {
        if (name == null) {
            return null;
        }
        Waystone old = byName.remove(name.toLowerCase(Locale.ROOT));
        if (old != null) {
            forEachOccupied(old, byPos::remove);
            markDirty();
        }
        return old;
    }

    // --- persistence ----------------------------------------------------------------------------

    public static final Codec<WaystoneState> CODEC = Waystone.CODEC.listOf().xmap(
            list -> {
                WaystoneState state = new WaystoneState();
                for (Waystone waystone : list) {
                    state.putLoaded(waystone);
                }
                return state;
            },
            state -> List.copyOf(state.byName.values())
    );

    private void putLoaded(Waystone waystone) {
        String key = waystone.name().toLowerCase(Locale.ROOT);
        byName.put(key, waystone);
        forEachOccupied(waystone, occupied -> byPos.put(occupied, key));
    }

    public static final PersistentStateType<WaystoneState> TYPE = new PersistentStateType<>(
            "waystones",
            WaystoneState::new,
            CODEC,
            null
    );
}
