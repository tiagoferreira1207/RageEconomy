package com.ragemines.rageeconomy;

import com.ragemines.rageeconomy.screen.EconomyScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public class RageEconomyClient implements ClientModInitializer {

    public static KeyBinding economyKey;

    @Override
    public void onInitializeClient() {
        economyKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.rageeconomy.open",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            "category.rageeconomy"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (economyKey.wasPressed()) {
                if (client.player != null) {
                    client.setScreen(new EconomyScreen());
                }
            }
        });
    }
}
