package dev.reinforcedclaims.command;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.reinforcedclaims.fellowship.FellowshipState;
import dev.reinforcedclaims.fellowship.FactionSync;
import dev.reinforcedclaims.fellowship.PlayerFactionData;
import dev.reinforcedclaims.protection.ProtectionManager;
import dev.reinforcedclaims.protection.ProtectionModes;
import dev.reinforcedclaims.fellowship.Fellowship;
import dev.reinforcedclaims.claim.ClaimManager;
import dev.reinforcedclaims.gui.ClaimManageScreen;
import dev.reinforcedclaims.gui.FellowshipListScreen;
import dev.reinforcedclaims.gui.FellowshipPickerScreen;
import dev.reinforcedclaims.gui.InviteScreen;
import dev.reinforcedclaims.gui.OutgoingInviteScreen;
import dev.reinforcedclaims.util.Players;
import dev.reinforcedclaims.util.ProtectionView;
import net.minecraft.command.CommandSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

// The /fs and /clm command trees.
public final class ReinforcedClaimsCommand {

    private ReinforcedClaimsCommand() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        LiteralCommandNode<ServerCommandSource> fellowship = dispatcher.register(literal("fs")
                .then(literal("create").executes(ReinforcedClaimsCommand::fellowshipCreate))
                .then(literal("faction").requires(s -> s.hasPermissionLevel(4))
                        .then(argument("name", StringArgumentType.string())
                                .executes(ctx -> fellowshipFaction(ctx, ""))
                                .then(argument("factionId", StringArgumentType.greedyString())
                                        .suggests(ReinforcedClaimsCommand::suggestFactions)
                                        .executes(ctx -> fellowshipFaction(ctx,
                                                StringArgumentType.getString(ctx, "factionId"))))))
                // Both invite lists are menus, already scoped to the caller.
                .then(literal("invites").executes(ReinforcedClaimsCommand::fellowshipInvites))
                .then(literal("uninvite").executes(ReinforcedClaimsCommand::fellowshipUninvite))
                // info/icon/pvp/disband/kick are roster buttons, not commands.
                .then(literal("list")
                        .executes(ctx -> fellowshipList(ctx, false))
                        .then(literal("all").requires(s -> s.hasPermissionLevel(4)).executes(ctx -> fellowshipList(ctx, true))))
                // The same toggle as /clm bypass.
                .then(literal("bypass").requires(s -> s.hasPermissionLevel(4)).executes(ReinforcedClaimsCommand::bypass)));

        // No assign-by-name and no unassign: fellowships are picked from a menu,
        // and grants are revoked in /clm manage.
        LiteralCommandNode<ServerCommandSource> claim = dispatcher.register(literal("clm")
                .then(literal("assign")
                        .executes(ReinforcedClaimsCommand::claimAssignFellowship)
                        .then(argument("player", StringArgumentType.word())
                                .suggests(ReinforcedClaimsCommand::suggestPlayers)
                                .executes(ReinforcedClaimsCommand::claimAssignPlayer)))
                // name and snitch are /clm manage header buttons, not commands.
                .then(literal("manage").executes(ReinforcedClaimsCommand::claimManage))
                .then(literal("reinforce").executes(ReinforcedClaimsCommand::claimReinforce))
                .then(literal("bypass").requires(s -> s.hasPermissionLevel(4)).executes(ReinforcedClaimsCommand::bypass))
                .then(literal("view").requires(s -> s.hasPermissionLevel(2)).executes(ReinforcedClaimsCommand::view))
                .then(literal("validate").requires(s -> s.hasPermissionLevel(4))
                        .executes(ReinforcedClaimsCommand::claimValidate)
                        .then(literal("all").executes(ReinforcedClaimsCommand::claimValidateAll))));

        dispatcher.register(literal("fellowship").redirect(fellowship));
        dispatcher.register(literal("claim").redirect(claim));
    }

    // --- claim/reinforcement access editing ------------------------------------------------------

    // Bare /clm assign: pick a fellowship to add to the looked-at block or claim.
    private static int claimAssignFellowship(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        Text refusal = ClaimManager.openFellowshipAssign(player);
        if (refusal != null) {
            Players.sendIfPresent(player, refusal, false);
            return 0;
        }
        return 1;
    }

    private static int claimAssignPlayer(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        GameProfile target = resolveProfile(ctx, player);
        if (target == null) {
            return 0;
        }
        Players.sendIfPresent(player, ClaimManager.assignPlayer(player, target.getId(), target.getName()), false);
        return 1;
    }

    // Opens /clm manage on the block or claim being looked at.
    private static int claimManage(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        ClaimManager.AccessTarget target = ClaimManager.resolveTarget(player);
        if (target == null) {
            return 0;
        }
        Text refusal = ClaimManager.editRefusal(player, target);
        if (refusal != null) {
            Players.sendIfPresent(player, refusal, false);
            return 0;
        }
        if (!ClaimManageScreen.open(player, target)) {
            return 0;
        }
        return 1;
    }

    // Toggles reinforce mode; while off, materials do nothing.
    private static int claimReinforce(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        boolean on = ProtectionModes.toggleReinforceMode(player.getUuid());
        player.sendMessage(Text.literal("Reinforcement " + (on ? "enabled" : "disabled"))
                .formatted(on ? Formatting.GREEN : Formatting.YELLOW), false);
        return 1;
    }

    private static int bypass(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        boolean on = ProtectionModes.toggleBypass(player.getUuid());
        player.sendMessage(Text.literal("Bypass " + (on ? "enabled" : "disabled"))
                .formatted(on ? Formatting.YELLOW : Formatting.GREEN), false);
        return 1;
    }

    // Toggles the protection overlay.
    private static int view(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        ProtectionView.Counts found = ProtectionView.toggle(player);
        if (found == null) {
            return 1;
        }
        player.sendMessage(Text.literal(found.reinforced() + " reinforced block(s), "
                + found.borderSegments() + " claim boundary line(s), "
                + found.claims() + " claim(s) in range; "
                + found.pieces() + " display(s) rendered")
                .formatted(Formatting.GRAY), false);
        return 1;
    }

    // Reconciles claim records against the world, in loaded chunks.
    private static int claimValidate(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        int removed = 0;
        for (ServerWorld world : source.getServer().getWorlds()) {
            removed += ClaimManager.validateLoaded(world);
        }
        int total = removed;
        source.sendFeedback(() -> Text.literal(total == 0
                        ? "All loaded claims valid"
                        : "Removed " + total + " invalid claim(s)")
                .formatted(total == 0 ? Formatting.GRAY : Formatting.YELLOW), false);
        return total;
    }

    // As above for the whole map, loading chunks a few per tick.
    private static int claimValidateAll(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        if (!ClaimManager.startSweep(source)) {
            source.sendFeedback(() -> Text.literal("Validating claims")
                    .formatted(Formatting.RED), false);
            return 0;
        }
        return 1;
    }

    // --- fellowship management -----------------------------------------------------------------------

    // Opens the same name prompt as the Create button on /fs list.
    private static int fellowshipCreate(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        FellowshipListScreen.promptCreate(ctx.getSource().getPlayerOrThrow(), false);
        return 1;
    }

    // Creates a faction, or rebinds an existing one's required faction.
    // An ordinary fellowship is never converted.
    private static int fellowshipFaction(CommandContext<ServerCommandSource> ctx, String requiredFaction)
            throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        MinecraftServer server = ctx.getSource().getServer();
        String name = StringArgumentType.getString(ctx, "name");
        FellowshipState fellowships = ProtectionManager.fellowships(server);
        if (!requiredFaction.isBlank() && !PlayerFactionData.isAvailable()) {
            player.sendMessage(Text.literal("Middle-earth player data not found; factions unrestricted")
                    .formatted(Formatting.RED), false);
            return 0;
        }
        // Op-only, so this looks server-wide. An exact id wins over a name match.
        Fellowship existing = fellowships.get(name);
        if (existing == null) {
            List<Fellowship> named = fellowships.named(name);
            if (named.size() > 1) {
                FellowshipPickerScreen.open(player, name, named, picked ->
                        factionRebind(player, fellowships, picked, requiredFaction));
                return 1;
            }
            existing = named.isEmpty() ? null : named.get(0);
        }
        if (existing != null) {
            return factionRebind(player, fellowships, existing, requiredFaction);
        }
        fellowships.put(Fellowship.faction(fellowships.freshId(name), name, player.getUuid(), requiredFaction));
        fellowships.markDirty();
        player.sendMessage(Text.literal("Created " + name + " faction"
                + (requiredFaction.isBlank() ? "" : ", restricted to " + requiredFaction))
                .formatted(Formatting.GREEN), false);
        warnUnknownFaction(player, requiredFaction);
        return 1;
    }

    // Repoints or clears an existing faction's restriction.
    private static int factionRebind(ServerPlayerEntity player, FellowshipState fellowships,
                                     Fellowship fellowship, String requiredFaction) {
        if (!fellowship.faction()) {
            player.sendMessage(Text.literal("Cannot restrict fellowships")
                    .formatted(Formatting.RED), false);
            return 0;
        }
        if (fellowship.requiredFaction().equalsIgnoreCase(requiredFaction)) {
            player.sendMessage(Text.literal(fellowship.name()
                    + (requiredFaction.isBlank() ? " unrestricted" : " restricted to " + requiredFaction))
                    .formatted(Formatting.GRAY), false);
            return 0;
        }
        Fellowship rebound = fellowship.withRequiredFaction(requiredFaction);
        fellowships.put(rebound);
        fellowships.markDirty();
        player.sendMessage(Text.literal(requiredFaction.isBlank()
                ? fellowship.name() + " unrestricted"
                : fellowship.name() + " restricted to " + requiredFaction)
                .formatted(Formatting.GREEN), false);
        warnUnknownFaction(player, requiredFaction);
        FactionSync.evictMismatched(player, rebound);
        return 1;
    }

    private static void warnUnknownFaction(ServerPlayerEntity player, String requiredFaction) {
        if (requiredFaction.isBlank() || PlayerFactionData.knownFactions().contains(requiredFaction)) {
            return;
        }
        player.sendMessage(Text.literal(requiredFaction + " is currently unknown").formatted(Formatting.YELLOW), false);
    }


    // Disband, kick, icon and PvP are menu buttons, not commands.

    // --- invites --------------------------------------------------------------------------------

    // Inviting is the roster's Invite button, not a command.

    // Opens the menu of invites the caller may withdraw.
    private static int fellowshipUninvite(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        OutgoingInviteScreen.open(ctx.getSource().getPlayerOrThrow());
        return 1;
    }

    // Opens the menu of invites waiting on the caller's answer.
    private static int fellowshipInvites(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        InviteScreen.open(ctx.getSource().getPlayerOrThrow());
        return 1;
    }

    private static CompletableFuture<Suggestions> suggestPlayers(CommandContext<ServerCommandSource> ctx,
                                                                SuggestionsBuilder builder) {
        return CommandSource.suggestMatching(ctx.getSource().getPlayerNames(), builder);
    }

    // Faction ids picked on this server, for tab completion.
    private static CompletableFuture<Suggestions> suggestFactions(CommandContext<ServerCommandSource> ctx,
                                                                  SuggestionsBuilder builder) {
        return CommandSource.suggestMatching(PlayerFactionData.knownFactions(), builder);
    }

    // The profile the player argument names, or null with the refusal already sent.
    private static GameProfile resolveProfile(CommandContext<ServerCommandSource> ctx, ServerPlayerEntity player) {
        String name = StringArgumentType.getString(ctx, "player");
        GameProfile profile = Players.profile(ctx.getSource().getServer(), name);
        if (profile == null) {
            player.sendMessage(Players.unknownPlayer(name), false);
        }
        return profile;
    }

    // Opens the caller's fellowship list, or every fellowship for all.
    private static int fellowshipList(CommandContext<ServerCommandSource> ctx, boolean all) throws CommandSyntaxException {
        FellowshipListScreen.open(ctx.getSource().getPlayerOrThrow(), all);
        return 1;
    }
}
