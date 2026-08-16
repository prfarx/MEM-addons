package dev.waystones.gui;

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
import java.util.function.Function;

// One-line text entry built on the anvil's rename field.
public final class NamePromptScreen {

    private NamePromptScreen() {
    }

    // Opens the prompt. validate returns a rejection message, or null to accept.
    public static void open(ServerPlayerEntity player, Text title, String initial,
                             Function<String, Text> validate, Consumer<String> onAccept) {
        Menus.open(player, new SimpleNamedScreenHandlerFactory(
                (syncId, inventory, ignored) -> new Handler(syncId, inventory, player, initial, validate, onAccept), title));
    }

    private static final class Handler extends AnvilScreenHandler {

        private final ServerPlayerEntity player;
        private final Function<String, Text> validate;
        private final Consumer<String> onAccept;
        private String typed;
        private Text rejection;

        private Handler(int syncId, PlayerInventory playerInventory, ServerPlayerEntity player,
                         String initial, Function<String, Text> validate, Consumer<String> onAccept) {
            super(syncId, playerInventory);
            this.player = player;
            this.validate = validate;
            this.onAccept = onAccept;
            this.typed = initial;
            this.input.setStack(0, Menus.stack(Items.NAME_TAG, Text.literal(initial).formatted(Formatting.WHITE), List.of()));
            updateResult();
        }

        @Override
        public boolean setNewItemName(String name) {
            typed = name;
            rejection = null;
            updateResult();
            sendContentUpdates();
            return true;
        }

        @Override
        public void updateResult() {
            String trimmed = typed.trim();
            if (trimmed.isEmpty()) {
                output.setStack(0, Menus.stack(Items.BARRIER, Menus.label("Confirm", Formatting.GRAY), List.of()));
            } else if (rejection != null) {
                output.setStack(0, Menus.stack(Items.BARRIER, Menus.label("Confirm", Formatting.RED), List.of(rejection)));
            } else {
                output.setStack(0, Menus.stack(Items.WRITABLE_BOOK, Menus.label("Confirm", Formatting.GREEN), List.of()));
            }
        }

        @Override
        protected boolean canTakeOutput(PlayerEntity clicker, boolean present) {
            return !typed.trim().isEmpty();
        }

        @Override
        protected void onTakeOutput(PlayerEntity clicker, ItemStack stack) {
            String answer = typed.trim();
            if (answer.isEmpty()) {
                return;
            }
            Text problem = validate.apply(answer);
            if (problem != null) {
                rejection = problem;
                updateResult();
                sendContentUpdates();
                return;
            }
            input.setStack(0, ItemStack.EMPTY);
            output.setStack(0, ItemStack.EMPTY);
            player.getServer().execute(() -> {
                ScreenHandler open = player.currentScreenHandler;
                onAccept.accept(answer);
                if (player.currentScreenHandler == open) {
                    player.closeHandledScreen();
                }
            });
        }

        @Override
        public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity clicker) {
            if (slotIndex != getResultSlotIndex() || !canTakeOutput(clicker, true)) {
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

        @Override
        public void onClosed(PlayerEntity closing) {
            input.setStack(0, ItemStack.EMPTY);
            output.setStack(0, ItemStack.EMPTY);
            super.onClosed(closing);
        }
    }
}
