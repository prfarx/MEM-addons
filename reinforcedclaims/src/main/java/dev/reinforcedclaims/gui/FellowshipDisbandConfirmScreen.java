package dev.reinforcedclaims.gui;

import dev.reinforcedclaims.claim.ClaimManager;
import dev.reinforcedclaims.fellowship.Fellowship;
import dev.reinforcedclaims.fellowship.FellowshipState;
import dev.reinforcedclaims.protection.ProtectionManager;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

// The confirmation step before a Disband click takes effect.
public final class FellowshipDisbandConfirmScreen {

    private FellowshipDisbandConfirmScreen() {
    }

    // Opens the prompt; all says which list to return to.
    public static void open(ServerPlayerEntity player, Fellowship fellowship, boolean all) {
        ConfirmScreen.open(player, Text.literal("Disband " + fellowship.name() + "?").formatted(Formatting.DARK_GRAY),
                (syncId, inventory, p) -> new Handler(syncId, inventory, p, fellowship.id(), all));
    }

    // --- screen handler ------------------------------------------------------------------------

    private static final class Handler extends ConfirmScreen {

        private final String key;
        private final boolean all;
        private Fellowship fellowship;

        private Handler(int syncId, PlayerInventory playerInventory, ServerPlayerEntity player, String key, boolean all) {
            super(syncId, playerInventory, player);
            this.key = key;
            this.all = all;
            refresh();
        }

        @Override
        protected boolean reload() {
            fellowship = ProtectionManager.fellowships(player.getServer()).get(key);
            return fellowship != null;
        }

        @Override
        protected ItemStack icon() {
            return Menus.stack(fellowship.iconItem(), Menus.label(fellowship.name(), Formatting.WHITE), List.of());
        }

        @Override
        protected String confirmLabel() {
            return "Disband";
        }

        @Override
        protected void onConfirm() {
            MinecraftServer server = player.getServer();
            FellowshipState store = ProtectionManager.fellowships(server);
            store.remove(fellowship.id());
            store.markDirty();
            ProtectionManager.invites(server).removeFellowship(fellowship.id());
            ClaimManager.pruneStaleGrants(server, fellowship.id());
            player.sendMessage(Text.literal(fellowship.name() + " disbanded").formatted(Formatting.RED), false);
        }

        @Override
        protected void onDone(boolean confirmed) {
            if (confirmed) {
                FellowshipListScreen.open(player, all);
            } else {
                FellowshipInfoScreen.open(player, fellowship, all);
            }
        }
    }
}
