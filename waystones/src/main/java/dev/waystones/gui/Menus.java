package dev.waystones.gui;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

// The stacks the chest menus are built from, and how they open.
final class Menus {

    private Menus() {
    }

    // Opens a menu in place of whatever is already open.
    static void open(ServerPlayerEntity player, NamedScreenHandlerFactory factory) {
        if (player.currentScreenHandler != player.playerScreenHandler) {
            player.onHandledScreenClosed();
        }
        player.openHandledScreen(factory);
    }

    static ItemStack stack(Item item, Text name, List<Text> lore) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponentTypes.CUSTOM_NAME, name);
        stack.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return stack;
    }

    // A non-italic label.
    static Text label(String text, Formatting colour) {
        return Text.literal(text).formatted(colour).styled(style -> style.withItalic(false));
    }
}
