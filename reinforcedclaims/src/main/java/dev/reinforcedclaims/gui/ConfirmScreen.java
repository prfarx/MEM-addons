package dev.reinforcedclaims.gui;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

// Shared "are you sure?" screen: an icon over Confirm and Cancel.
abstract class ConfirmScreen extends PagedMenu {

    private static final int ICON_SLOT = 1 * 9 + 4;
    private static final int CONFIRM_SLOT = 3 * 9 + 2;
    private static final int CANCEL_SLOT = 3 * 9 + 6;

    protected ConfirmScreen(int syncId, PlayerInventory playerInventory, ServerPlayerEntity player) {
        super(syncId, playerInventory, player);
    }

    // A subclass's constructor, so open() needn't know the concrete type.
    interface Factory {
        ConfirmScreen create(int syncId, PlayerInventory playerInventory, ServerPlayerEntity player);
    }

    static void open(ServerPlayerEntity player, Text title, Factory factory) {
        Menus.open(player, new SimpleNamedScreenHandlerFactory(
                (syncId, inventory, ignored) -> factory.create(syncId, inventory, player), title));
    }

    // The item representing the subject.
    protected abstract ItemStack icon();

    // The confirm button's label.
    protected abstract String confirmLabel();

    // Runs the confirmed action.
    protected abstract void onConfirm();

    // Where to go once the prompt is answered.
    protected abstract void onDone(boolean confirmed);

    @Override
    protected final int pageSize() {
        return 1;
    }

    @Override
    protected final int itemCount() {
        return 1;
    }

    @Override
    protected final void renderPage() {
        inventory.setStack(ICON_SLOT, icon());
        inventory.setStack(CONFIRM_SLOT, Menus.stack(Items.RED_CONCRETE,
                Menus.label(confirmLabel(), Formatting.RED), List.of()));
        inventory.setStack(CANCEL_SLOT, Menus.stack(Items.GRAY_CONCRETE,
                Menus.label("Cancel", Formatting.GRAY), List.of()));
    }

    @Override
    protected final void onContentClick(int row, int column) {
        int slot = row * 9 + column;
        if (slot != CONFIRM_SLOT && slot != CANCEL_SLOT) {
            return;
        }
        click();
        if (slot == CONFIRM_SLOT) {
            onConfirm();
        }
        onDone(slot == CONFIRM_SLOT);
    }
}
