package dev.reinforcedclaims.protection;

import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// Who has a block's or claim's permissions open for editing. Not persisted.
public final class AccessLocks {

    // One editable target; the flag separates a reinforcement from a claim at the same position.
    public record Key(RegistryKey<World> world, BlockPos pos, boolean reinforcement) {
        public Key {
            pos = pos.toImmutable();
        }
    }

    private static final Map<Key, UUID> EDITING = new ConcurrentHashMap<>();

    private AccessLocks() {
    }

    // Takes or keeps the lock; false when somebody else holds it.
    public static boolean acquire(Key key, UUID id) {
        UUID holder = EDITING.putIfAbsent(key, id);
        return holder == null || holder.equals(id);
    }

    // Drops the lock, only if this player holds it.
    public static void release(Key key, UUID id) {
        EDITING.remove(key, id);
    }

    // The player editing this target, or null when free.
    public static UUID holder(Key key) {
        return EDITING.get(key);
    }

    // Drops every lock a player holds, on disconnect.
    public static void clear(UUID id) {
        Iterator<Map.Entry<Key, UUID>> it = EDITING.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().equals(id)) {
                it.remove();
            }
        }
    }

    public static void clear() {
        EDITING.clear();
    }
}
