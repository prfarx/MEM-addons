package dev.reinforcedclaims.gui;

import dev.reinforcedclaims.config.Config;
import dev.reinforcedclaims.fellowship.Fellowship;
import dev.reinforcedclaims.fellowship.FellowshipInvite;
import dev.reinforcedclaims.fellowship.Role;
import dev.reinforcedclaims.protection.ProtectionManager;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;
import java.util.UUID;

// The row pieces the two invite menus share, each coping with a fellowship
// disbanded or renamed since the invite was sent.
final class Invites {

    private Invites() {
    }

    // The fellowship an invite names, or null once disbanded.
    static Fellowship fellowshipOf(MinecraftServer server, FellowshipInvite invite) {
        return ProtectionManager.fellowships(server).get(invite.key());
    }

    // The sender's current rank, falling back to Guest.
    static Role senderRole(Fellowship fellowship, UUID inviter) {
        Role role = fellowship != null ? fellowship.roleOf(inviter) : null;
        return role != null ? role : Role.GUEST;
    }

    // How that rank reads.
    static String rankLabel(Fellowship fellowship, Role role) {
        return fellowship != null ? fellowship.labelOf(role) : role.label(false);
    }

    // The fellowship's icon and current name, falling back to what the invite snapshotted.
    static ItemStack icon(Fellowship fellowship, FellowshipInvite invite) {
        Item item = fellowship != null ? fellowship.iconItem() : Config.get().fellowshipIcon("");
        String name = fellowship != null ? fellowship.name() : invite.fellowship();
        return Menus.stack(item, Menus.label(name, Formatting.WHITE), List.<Text>of());
    }
}
