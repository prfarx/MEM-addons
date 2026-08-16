package dev.reinforcedclaims;

import dev.reinforcedclaims.command.ReinforcedClaimsCommand;
import dev.reinforcedclaims.config.Config;
import dev.reinforcedclaims.event.ClaimEvents;
import dev.reinforcedclaims.event.ProtectionEvents;
import dev.reinforcedclaims.event.PvpEvents;
import dev.reinforcedclaims.fellowship.PlayerFactionData;
import dev.reinforcedclaims.gui.InvitePrompt;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ReinforcedClaims implements ModInitializer {

    public static final String MOD_ID = "reinforcedclaims";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        Config.load();
        ProtectionEvents.register();
        ClaimEvents.register();
        PvpEvents.register();
        InvitePrompt.register();
        PlayerFactionData.register();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                ReinforcedClaimsCommand.register(dispatcher));

        LOGGER.info("Reinforced Claims loaded");
    }
}
