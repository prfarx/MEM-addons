package dev.waystones;

import dev.waystones.command.WaystoneCommand;
import dev.waystones.config.Config;
import dev.waystones.event.WaystoneEvents;
import dev.waystones.waystone.WaystoneSkin;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Waystones implements ModInitializer {

    public static final String MOD_ID = "waystones";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        Config.load();
        WaystoneSkin.load(Config.get());
        WaystoneEvents.register();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                WaystoneCommand.register(dispatcher));

        LOGGER.info("Waystones loaded");
    }
}
