package com.ragemines.rageeconomy;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RageEconomy implements ModInitializer {
    public static final String MOD_ID = "rageeconomy";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("RageEconomy initialized.");
    }
}
