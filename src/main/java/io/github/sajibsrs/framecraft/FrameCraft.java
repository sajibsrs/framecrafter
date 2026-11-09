package io.github.sajibsrs.framecraft;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FrameCraft implements ClientModInitializer {
	public static final String MOD_ID = "framecraft";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static KeyMapping toggleKey;

	@Override
	public void onInitializeClient() {
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, MOD_ID));
		toggleKey = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.framecraft.toggle", InputConstants.KEY_V, category));

		ClientLifecycleEvents.CLIENT_STARTED.register(SceneDepth::register);

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (toggleKey.consumeClick()) {
				if (mc.player != null) {
					CameraState.toggle();
				}
			}
		});
	}
}
