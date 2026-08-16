package dev.waystones.item;

import dev.waystones.waystone.WaystoneSkin;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

// The waystone-placer item: a vanilla block item tagged with a skin id.
public final class WaystoneItem {

    private static final String SKIN_KEY = "WaystonesSkin";

    private WaystoneItem() {
    }

    // One waystone-placer for this skin.
    public static ItemStack create(WaystoneSkin skin) {
        ItemStack stack = new ItemStack(skin.baseItem());
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Waystone (" + displayName(skin) + ")")
                .formatted(Formatting.GOLD).styled(style -> style.withItalic(false)));
        stack.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal("Right-click a block face to place.").formatted(Formatting.GRAY).styled(s -> s.withItalic(false)))));
        NbtCompound tag = new NbtCompound();
        tag.putString(SKIN_KEY, skin.id());
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(tag));
        return stack;
    }

    // The skin this stack places, or null if it isn't a placer.
    public static WaystoneSkin skinOf(ItemStack stack) {
        NbtComponent data = stack.getOrDefault(DataComponentTypes.CUSTOM_DATA, NbtComponent.DEFAULT);
        if (data.isEmpty() || !data.contains(SKIN_KEY)) {
            return null;
        }
        return WaystoneSkin.byId(data.copyNbt().getString(SKIN_KEY, ""));
    }

    private static String displayName(WaystoneSkin skin) {
        String id = skin.id();
        return Character.toUpperCase(id.charAt(0)) + id.substring(1);
    }
}
