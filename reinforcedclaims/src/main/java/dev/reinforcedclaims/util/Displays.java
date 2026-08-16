package dev.reinforcedclaims.util;

import dev.reinforcedclaims.mixin.ClientConnectionAccessor;
import dev.reinforcedclaims.mixin.EntityIdAccessor;
import dev.reinforcedclaims.mixin.NetworkHandlerAccessor;
import io.netty.channel.Channel;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtDouble;
import net.minecraft.nbt.NbtFloat;
import net.minecraft.nbt.NbtList;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.handler.PacketBundleHandler;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BundleS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Text displays sent to one client and never added to the world. Each carries a single
// space, so all it draws is its background rectangle: one face of a wireframe line.
public final class Displays {

    // Multiplier on the client's entity render distance.
    private static final float VIEW_RANGE = 4.0f;

    // High half of every display's UUID, so ours don't collide.
    private static final long UUID_SALT = 0x7461_6B65_6177_6179L;

    // --- background geometry --------------------------------------------------------------------
    //
    // The client's background rectangle, before the entity's own transformation.

    // Ten font pixels: the rectangle's height. This axis carries a line's length.
    private static final float QUAD_HEIGHT = 0.25f;

    // Five font pixels: a space's advance plus left padding. This axis carries a line's thickness.
    private static final float QUAD_WIDTH = 0.125f;

    // Where the rectangle's centre sits relative to the display.
    private static final float QUAD_CENTER_X = 0.0125f;
    private static final float QUAD_CENTER_Y = 0.125f;

    // What reaches the client as tracked data rather than packet fields.
    private record Style(Wireframe.Axis length, Wireframe.Axis thickness, boolean flipped,
                         float span, float width, int color, boolean seeThrough) {
    }

    // Cached styles. Bounded because the key carries span, which has no natural ceiling.
    private static final int MAX_STYLES = 4096;

    private static final Map<Style, List<DataTracker.SerializedEntry<?>>> TRACKED =
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Style, List<DataTracker.SerializedEntry<?>>> eldest) {
                    return size() > MAX_STYLES;
                }
            };

    private Displays() {
    }

    // A network id no real entity will be given.
    public static int nextId() {
        return EntityIdAccessor.reinforcedclaims$currentId().incrementAndGet();
    }

    // Drops the style cache, e.g. on server stop.
    public static void clear() {
        TRACKED.clear();
    }

    // --- delivery -------------------------------------------------------------------------------

    // Packets per bundle, leaving room for delimiters.
    private static final int BUNDLE_LIMIT = PacketBundleHandler.MAX_PACKETS - 2;

    // Sends a redraw as bundles, so the client applies each in one frame.
    public static void sendBundled(ServerPlayerEntity viewer, List<Packet<? super ClientPlayPacketListener>> packets) {
        for (int from = 0; from < packets.size(); from += BUNDLE_LIMIT) {
            int to = Math.min(from + BUNDLE_LIMIT, packets.size());
            viewer.networkHandler.sendPacket(new BundleS2CPacket(packets.subList(from, to)));
        }
    }

    // The packet that removes these displays from a client.
    public static Packet<? super ClientPlayPacketListener> destroyPacket(int[] ids) {
        return new EntitiesDestroyS2CPacket(ids);
    }

    // Queues one face of a line, centred on pos. Faces are culled from behind, so a
    // plane takes one of each flip. False when the display type didn't resolve.
    public static boolean addQuad(List<Packet<? super ClientPlayPacketListener>> into,
                                  ServerWorld world, int id,
                                  double x, double y, double z,
                                  Wireframe.Axis length, Wireframe.Axis thickness, boolean flipped,
                                  float span, float width, int color, boolean seeThrough) {
        List<DataTracker.SerializedEntry<?>> data =
                trackedData(world, length, thickness, flipped, span, width, color, seeThrough);
        if (data == null) {
            return false;
        }
        into.add(new EntitySpawnS2CPacket(
                id, new UUID(UUID_SALT, id),
                x, y, z,
                0f, 0f,
                EntityType.TEXT_DISPLAY, 0, Vec3d.ZERO, 0f));
        into.add(new EntityTrackerUpdateS2CPacket(id, data));
        return true;
    }

    // Whether the client is keeping up, per Netty's outbound buffer.
    public static boolean keepingUp(ServerPlayerEntity viewer) {
        ClientConnection connection = ((NetworkHandlerAccessor) viewer.networkHandler).reinforcedclaims$connection();
        Channel channel = ((ClientConnectionAccessor) connection).reinforcedclaims$channel();
        return channel == null || channel.isWritable();
    }

    // --- tracked data ---------------------------------------------------------------------------

    // The tracked-data entries for one style, built once. Position travels in the spawn packet.
    private static List<DataTracker.SerializedEntry<?>> trackedData(ServerWorld world,
                                                                    Wireframe.Axis length, Wireframe.Axis thickness,
                                                                    boolean flipped, float span, float width,
                                                                    int color, boolean seeThrough) {
        Style style = new Style(length, thickness, flipped, span, width, color, seeThrough);
        List<DataTracker.SerializedEntry<?>> cached = TRACKED.get(style);
        if (cached != null) {
            return cached;
        }
        Entity prototype = build(world, nbt(length, thickness, flipped, span, width, color, seeThrough));
        if (prototype == null) {
            return null;
        }
        List<DataTracker.SerializedEntry<?>> data = prototype.getDataTracker().getChangedEntries();
        TRACKED.put(style, data);
        return data;
    }

    // --- nbt ------------------------------------------------------------------------------------

    // A prototype face's NBT; only the tracked-data fields matter.
    private static NbtCompound nbt(Wireframe.Axis length, Wireframe.Axis thickness, boolean flipped,
                                   float span, float width, int color, boolean seeThrough) {
        NbtCompound nbt = new NbtCompound();
        nbt.putString("id", "minecraft:text_display");
        nbt.put("Pos", doubles(0.0, 0.0, 0.0));
        nbt.put("Rotation", floats(0.0f, 0.0f));
        nbt.putFloat("view_range", VIEW_RANGE);
        nbt.putFloat("width", 0.0f);
        nbt.putFloat("height", 0.0f);
        nbt.putString("text", " ");
        nbt.putInt("background", color);
        nbt.putBoolean("default_background", false);
        nbt.putBoolean("see_through", seeThrough);
        nbt.putBoolean("shadow", false);
        NbtCompound brightness = new NbtCompound();
        brightness.putInt("block", 15);
        brightness.putInt("sky", 15);
        nbt.put("brightness", brightness);
        nbt.put("transformation", transformation(length, thickness, flipped, span, width));
        return nbt;
    }

    // Scales the rectangle to the line, turns it to face the right way, and recentres it.
    // Composed as translation x left rotation x scale x right rotation.
    private static NbtCompound transformation(Wireframe.Axis length, Wireframe.Axis thickness,
                                              boolean flipped, float span, float width) {
        float scaleX = width / QUAD_WIDTH;
        float scaleY = span / QUAD_HEIGHT;
        Quaternionf rotation = orientation(length, thickness, flipped);
        Vector3f offset = new Vector3f(QUAD_CENTER_X * scaleX, QUAD_CENTER_Y * scaleY, 0.0f)
                .rotate(rotation)
                .negate();
        NbtCompound t = new NbtCompound();
        t.put("translation", floats(offset.x, offset.y, offset.z));
        t.put("scale", floats(scaleX, scaleY, 1.0f));
        t.put("left_rotation", floats(rotation.x, rotation.y, rotation.z, rotation.w));
        t.put("right_rotation", floats(0.0f, 0.0f, 0.0f, 1.0f));
        return t;
    }

    // The rotation taking the rectangle's axes onto the world's. Built as a basis so all
    // twelve pairings come out right-handed.
    private static Quaternionf orientation(Wireframe.Axis length, Wireframe.Axis thickness, boolean flipped) {
        Vector3f up = unit(length);
        Vector3f right = unit(thickness);
        Vector3f normal = new Vector3f(right).cross(up);
        if (flipped) {
            right.negate();
            normal.negate();
        }
        Matrix3f basis = new Matrix3f().setColumn(0, right).setColumn(1, up).setColumn(2, normal);
        return new Quaternionf().setFromNormalized(basis);
    }

    private static Vector3f unit(Wireframe.Axis axis) {
        return switch (axis) {
            case X -> new Vector3f(1.0f, 0.0f, 0.0f);
            case Y -> new Vector3f(0.0f, 1.0f, 0.0f);
            case Z -> new Vector3f(0.0f, 0.0f, 1.0f);
        };
    }

    // Null when the entity type id doesn't resolve.
    private static Entity build(ServerWorld world, NbtCompound nbt) {
        return EntityType.loadEntityWithPassengers(nbt, world, SpawnReason.COMMAND, entity -> entity);
    }

    private static NbtList doubles(double... values) {
        NbtList list = new NbtList();
        for (double value : values) {
            list.add(NbtDouble.of(value));
        }
        return list;
    }

    private static NbtList floats(float... values) {
        NbtList list = new NbtList();
        for (float value : values) {
            list.add(NbtFloat.of(value));
        }
        return list;
    }
}
