package dev.reinforcedclaims.gui;

import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;
import java.util.function.Consumer;

// One-line text entry built on the anvil's rename field. The left item's name is
// the text the field starts on; the client mirrors one into the other.
public final class TextPromptScreen {

    private TextPromptScreen() {
    }

    // Opens the prompt; onSubmit runs with the trimmed text on confirm.
    public static void open(ServerPlayerEntity player, Text title, String initial, Consumer<String> onSubmit) {
        open(player, title, initial, null, null, onSubmit);
    }

    // As above, with hint shown as lore under the item.
    public static void open(ServerPlayerEntity player, Text title, String initial, String hint,
                            Consumer<String> onSubmit) {
        open(player, title, initial, hint, null, onSubmit);
    }

    // As above, but an empty field is an answer of its own, submitted as "".
    public static void open(ServerPlayerEntity player, Text title, String initial, String hint, String emptyLabel,
                            Consumer<String> onSubmit) {
        Menus.open(player, new SimpleNamedScreenHandlerFactory(
                (syncId, inventory, ignored) -> new Handler(syncId, inventory, player, initial, hint, emptyLabel, onSubmit),
                title));
    }

    private static final class Handler extends AnvilScreenHandler {

        private final ServerPlayerEntity player;
        // What confirming an empty field means, or null when it is no answer at all.
        private final String emptyLabel;
        private final Consumer<String> onSubmit;
        private String typed;

        private Handler(int syncId, PlayerInventory playerInventory, ServerPlayerEntity player,
                        String initial, String hint, String emptyLabel, Consumer<String> onSubmit) {
            // No-context constructor: not tied to a real anvil in the world.
            super(syncId, playerInventory);
            this.player = player;
            this.emptyLabel = emptyLabel;
            this.onSubmit = onSubmit;
            this.typed = initial;
            List<Text> lore = hint == null ? List.of() : List.of(Menus.label(hint, Formatting.DARK_GRAY));
            this.input.setStack(0, Menus.stack(Items.NAME_TAG, Text.literal(initial).formatted(Formatting.WHITE), lore));
            updateResult();
        }

        // Every keystroke arrives here.
        @Override
        public boolean setNewItemName(String name) {
            typed = name;
            updateResult();
            sendContentUpdates();
            return true;
        }

        // The confirm slot's item and colour say whether it is live.
        @Override
        public void updateResult() {
            String trimmed = typed.trim();
            if (!trimmed.isEmpty()) {
                output.setStack(0, Menus.stack(Items.WRITABLE_BOOK, Menus.label("Confirm", Formatting.GREEN), List.of()));
            } else if (emptyLabel != null) {
                output.setStack(0, Menus.stack(Items.WRITABLE_BOOK, Menus.label("Confirm", Formatting.YELLOW),
                        List.of(Menus.label(emptyLabel, Formatting.DARK_GRAY))));
            } else {
                output.setStack(0, Menus.stack(Items.BARRIER, Menus.label("Confirm", Formatting.GRAY), List.of()));
            }
        }

        @Override
        protected boolean canTakeOutput(PlayerEntity clicker, boolean present) {
            return !typed.trim().isEmpty() || emptyLabel != null;
        }

        @Override
        protected void onTakeOutput(PlayerEntity clicker, ItemStack stack) {
            String answer = typed.trim();
            // Cleared first: the superclass would otherwise hand the label item back.
            input.setStack(0, ItemStack.EMPTY);
            output.setStack(0, ItemStack.EMPTY);
            player.getServer().execute(() -> {
                ScreenHandler open = player.currentScreenHandler;
                if (!answer.isEmpty() || emptyLabel != null) {
                    onSubmit.accept(answer);
                }
                // Only close when the continuation didn't already replace this handler.
                if (player.currentScreenHandler == open) {
                    player.closeHandledScreen();
                }
            });
        }

        // Only the confirm slot is live; every other click is swallowed.
        @Override
        public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity clicker) {
            if (slotIndex != getResultSlotIndex()) {
                return;
            }
            if (!canTakeOutput(clicker, true)) {
                return;
            }
            player.playSoundToPlayer(SoundEvents.UI_BUTTON_CLICK.value(), SoundCategory.MASTER, 0.4f, 1.0f);
            onTakeOutput(clicker, output.getStack(0));
        }

        @Override
        public ItemStack quickMove(PlayerEntity clicker, int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean canUse(PlayerEntity clicker) {
            return true;
        }

        @Override
        protected boolean canUse(BlockState state) {
            return true;
        }

        // Nothing is left in the slots to give back.
        @Override
        public void onClosed(PlayerEntity closing) {
            input.setStack(0, ItemStack.EMPTY);
            output.setStack(0, ItemStack.EMPTY);
            super.onClosed(closing);
        }
    }
}
