package dev.reinforcedclaims.gui;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.MathHelper;

// Shared paging skeleton for the chest menus; every click is intercepted.
// Subclasses must call refresh() at the end of their own constructor.
abstract class PagedMenu extends GenericContainerScreenHandler {

    protected final ServerPlayerEntity player;
    protected final SimpleInventory inventory;
    protected int page;

    protected PagedMenu(int syncId, PlayerInventory playerInventory, ServerPlayerEntity player) {
        this(syncId, playerInventory, player, new SimpleInventory(Menus.SIZE));
    }

    private PagedMenu(int syncId, PlayerInventory playerInventory, ServerPlayerEntity player,
                      SimpleInventory inventory) {
        super(ScreenHandlerType.GENERIC_9X6, syncId, playerInventory, inventory, Menus.ROWS);
        this.player = player;
        this.inventory = inventory;
    }

    // How many entries one page holds.
    protected abstract int pageSize();

    // Re-reads the live data; false when the menu's subject is gone and it should close.
    protected abstract boolean reload();

    // Total entries across every page.
    protected abstract int itemCount();

    // Draws this page's stacks; page is already clamped.
    protected abstract void renderPage();

    // A click anywhere but the nav row.
    protected void onContentClick(int row, int column) {
    }

    // Rebuilds the menu from live data; false when there is nothing left to show.
    protected final boolean refresh() {
        if (!reload()) {
            return false;
        }
        int pages = Math.max(1, (itemCount() + pageSize() - 1) / pageSize());
        page = MathHelper.clamp(page, 0, pages - 1);
        inventory.clear();
        renderPage();
        int nav = Menus.NAV_ROW * 9;
        if (page > 0) {
            inventory.setStack(nav, Menus.arrow("◀", page, pages));
        }
        if (page < pages - 1) {
            inventory.setStack(nav + 8, Menus.arrow("▶", page + 2, pages));
        }
        sendContentUpdates();
        return true;
    }

    protected final void refreshOrClose() {
        if (!refresh()) {
            player.getServer().execute(player::closeHandledScreen);
        }
    }

    protected final void click() {
        player.playSoundToPlayer(SoundEvents.UI_BUTTON_CLICK.value(), SoundCategory.MASTER, 0.4f, 1.0f);
    }

    @Override
    public final void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity clicker) {
        if (slotIndex < 0 || slotIndex >= Menus.SIZE) {
            return;
        }
        int row = slotIndex / 9;
        int column = slotIndex % 9;
        if (row != Menus.NAV_ROW) {
            onContentClick(row, column);
            return;
        }
        if (column == 0 && page > 0) {
            page--;
        } else if (column == 8 && (page + 1) * pageSize() < itemCount()) {
            page++;
        } else {
            return;
        }
        click();
        refreshOrClose();
    }

    @Override
    public final ItemStack quickMove(PlayerEntity clicker, int slot) {
        return ItemStack.EMPTY;
    }

    // Vanilla closes the menu once this goes false; block-bound menus override it.
    @Override
    public boolean canUse(PlayerEntity clicker) {
        return true;
    }
}
