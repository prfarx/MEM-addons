package dev.reinforcedclaims.gui;

import dev.reinforcedclaims.fellowship.Fellowship;
import dev.reinforcedclaims.fellowship.FellowshipState;
import dev.reinforcedclaims.fellowship.Role;
import dev.reinforcedclaims.protection.ProtectionManager;
import dev.reinforcedclaims.util.Players;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// The confirmation step before promoting someone to Owner hands the fellowship over.
public final class FellowshipTransferConfirmScreen {

    private FellowshipTransferConfirmScreen() {
    }

    // Opens the prompt for handing the fellowship over.
    public static void open(ServerPlayerEntity player, Fellowship fellowship, UUID target, boolean all) {
        String name = Players.name(player.getServer(), target);
        ConfirmScreen.open(player,
                Text.literal("Transfer " + fellowship.name() + " to " + (name == null ? "them" : name) + "?")
                        .formatted(Formatting.DARK_GRAY),
                (syncId, inventory, p) -> new Handler(syncId, inventory, p, fellowship.id(), target, all));
    }

    // --- screen handler ------------------------------------------------------------------------

    private static final class Handler extends ConfirmScreen {

        private final String key;
        private final UUID target;
        private final boolean all;
        private Fellowship fellowship;

        private Handler(int syncId, PlayerInventory playerInventory, ServerPlayerEntity player, String key,
                        UUID target, boolean all) {
            super(syncId, playerInventory, player);
            this.key = key;
            this.target = target;
            this.all = all;
            refresh();
        }

        @Override
        protected boolean reload() {
            fellowship = ProtectionManager.fellowships(player.getServer()).get(key);
            return fellowship != null && fellowship.isMember(target);
        }

        @Override
        protected ItemStack icon() {
            return Menus.head(target, Players.name(player.getServer(), target), Formatting.WHITE, List.of());
        }

        @Override
        protected String confirmLabel() {
            return "Transfer";
        }

        @Override
        protected void onConfirm() {
            MinecraftServer server = player.getServer();
            UUID outgoing = fellowship.owner();
            List<Fellowship.Member> members = new ArrayList<>(fellowship.members());
            members.removeIf(m -> m.id().equals(target));
            members.add(new Fellowship.Member(outgoing, Role.GUIDE));

            FellowshipState store = ProtectionManager.fellowships(server);
            fellowship = fellowship.withOwner(target, members);
            store.put(fellowship);
            store.markDirty();

            String name = Players.name(server, target);
            player.sendMessage(Text.literal(fellowship.name() + " transferred to " + name).formatted(Formatting.GRAY), false);
            ServerPlayerEntity online = server.getPlayerManager().getPlayer(target);
            if (online != null) {
                online.sendMessage(Text.literal(Players.name(server, outgoing) + " transferred "
                        + fellowship.name() + " to you").formatted(Formatting.GRAY), false);
            }
        }

        @Override
        protected void onDone(boolean confirmed) {
            FellowshipInfoScreen.open(player, fellowship, all);
        }
    }
}
