package dev.waystones.event;

import dev.waystones.gui.NamePromptScreen;
import dev.waystones.gui.TravelMenu;
import dev.waystones.item.WaystoneItem;
import dev.waystones.teleport.TeleportService;
import dev.waystones.waystone.Waystone;
import dev.waystones.waystone.WaystoneSkin;
import dev.waystones.waystone.WaystoneState;
import dev.waystones.waystone.WaystoneStructure;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.UUID;

// Event hooks: placement, right-click routing, break protection.
public final class WaystoneEvents {

    private WaystoneEvents() {
    }

    public static void register() {
        UseBlockCallback.EVENT.register(WaystoneEvents::onUseBlock);
        PlayerBlockBreakEvents.BEFORE.register(WaystoneEvents::onBreakAttempt);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                TeleportService.clear(handler.getPlayer().getUuid()));
    }

    private static ActionResult onUseBlock(PlayerEntity player, net.minecraft.world.World world,
                                            net.minecraft.util.Hand hand, BlockHitResult hitResult) {
        if (world.isClient() || !(player instanceof ServerPlayerEntity serverPlayer)) {
            return ActionResult.PASS;
        }
        ServerWorld serverWorld = (ServerWorld) world;
        WaystoneState state = WaystoneState.get(serverWorld.getServer());
        BlockPos clicked = hitResult.getBlockPos();
        Identifier dimension = serverWorld.getRegistryKey().getValue();

        Waystone existing = state.at(dimension, clicked);
        if (existing != null) {
            return onUseExisting(serverPlayer, state, existing);
        }

        if (hand != net.minecraft.util.Hand.MAIN_HAND) {
            return ActionResult.PASS;
        }
        ItemStack held = serverPlayer.getMainHandStack();
        WaystoneSkin skin = WaystoneItem.skinOf(held);
        if (skin == null) {
            return ActionResult.PASS;
        }
        return onPlace(serverPlayer, serverWorld, state, dimension, clicked, hitResult.getSide(), skin, held);
    }

    private static ActionResult onUseExisting(ServerPlayerEntity player, WaystoneState state, Waystone waystone) {
        if (player.isSneaking() && player.hasPermissionLevel(4)) {
            Waystone target = waystone;
            NamePromptScreen.open(player, Text.literal("Name this waystone"), target.named() ? target.name() : "",
                    newName -> validateName(state, target, newName),
                    newName -> renameWaystone(state, target, newName));
            return ActionResult.SUCCESS;
        }
        if (!waystone.named()) {
            player.sendMessage(Text.literal("This waystone hasn't been named yet.").formatted(Formatting.RED), true);
            return ActionResult.SUCCESS;
        }
        TravelMenu.open(player, waystone);
        return ActionResult.SUCCESS;
    }

    private static Text validateName(WaystoneState state, Waystone waystone, String newName) {
        if (state.exists(newName) && !newName.equalsIgnoreCase(waystone.name())) {
            return Text.literal("A waystone is already named that.").formatted(Formatting.RED);
        }
        return null;
    }

    private static void renameWaystone(WaystoneState state, Waystone waystone, String newName) {
        Waystone current = state.get(waystone.name());
        if (current == null) {
            return;
        }
        state.remove(current.name());
        state.put(current.renamed(newName));
    }

    private static ActionResult onPlace(ServerPlayerEntity player, ServerWorld world, WaystoneState state,
                                         Identifier dimension, BlockPos clicked, Direction side,
                                         WaystoneSkin skin, ItemStack held) {
        if (!player.hasPermissionLevel(4)) {
            player.sendMessage(Text.literal("You must be an operator to place a waystone.").formatted(Formatting.RED), true);
            player.playerScreenHandler.syncState();
            return ActionResult.FAIL;
        }
        BlockPos anchor = clicked.offset(side);
        if (!world.getBlockState(anchor).isAir() || !world.getBlockState(anchor.up()).isAir()) {
            player.sendMessage(Text.literal("There's no room to place a waystone there.").formatted(Formatting.RED), true);
            player.playerScreenHandler.syncState();
            return ActionResult.FAIL;
        }

        var displayEntities = WaystoneStructure.place(world, anchor, skin);
        Waystone waystone = new Waystone(freshPlaceholderName(state), false, skin.id(), dimension, anchor,
                player.getHorizontalFacing(), displayEntities);
        state.put(waystone);

        if (!player.getAbilities().creativeMode) {
            held.decrement(1);
        }
        return ActionResult.SUCCESS;
    }

    private static String freshPlaceholderName(WaystoneState state) {
        String name;
        do {
            name = "unnamed-" + UUID.randomUUID().toString().substring(0, 8);
        } while (state.exists(name));
        return name;
    }

    private static boolean onBreakAttempt(net.minecraft.world.World world, PlayerEntity player, BlockPos pos,
                                           net.minecraft.block.BlockState blockState,
                                           net.minecraft.block.entity.BlockEntity blockEntity) {
        if (world.isClient() || !(player instanceof ServerPlayerEntity serverPlayer)) {
            return true;
        }
        ServerWorld serverWorld = (ServerWorld) world;
        WaystoneState state = WaystoneState.get(serverWorld.getServer());
        Identifier dimension = serverWorld.getRegistryKey().getValue();
        Waystone waystone = state.at(dimension, pos);
        if (waystone == null) {
            return true;
        }
        if (!serverPlayer.hasPermissionLevel(4)) {
            serverPlayer.sendMessage(Text.literal("You must be an operator to remove a waystone.").formatted(Formatting.RED), true);
            serverPlayer.playerScreenHandler.syncState();
            return false;
        }
        state.remove(waystone.name());
        WaystoneStructure.remove(serverWorld, waystone.pos(), waystone.displayEntities());
        return false;
    }
}
