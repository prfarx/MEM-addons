package dev.reinforcedclaims.gui;

import dev.reinforcedclaims.protection.ProtectionManager;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

// Tells a player about invites waiting on them.
public final class InvitePrompt {

    private InvitePrompt() {
    }

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> notifyPending(handler.getPlayer()));
    }

    // Reports the unanswered count with a link into the menu; false when there are none.
    public static boolean notifyPending(ServerPlayerEntity player) {
        int count = ProtectionManager.invites(player.getServer()).invitesFor(player.getUuid()).size();
        if (count == 0) {
            return false;
        }
        player.sendMessage(Text.literal(count + " pending invite(s)")
                .formatted(Formatting.YELLOW)
                .append(Text.literal("/fs invites").styled(style -> style
                        .withColor(Formatting.WHITE)
                        .withBold(true)
                        .withClickEvent(new ClickEvent.RunCommand("/fs invites"))
                        .withHoverEvent(new HoverEvent.ShowText(Text.literal("Check invites"))))));
        return true;
    }
}
