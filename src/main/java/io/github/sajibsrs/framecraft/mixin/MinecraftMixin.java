package io.github.sajibsrs.framecraft.mixin;

import io.github.sajibsrs.framecraft.CameraState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** In camera mode left click is the shutter and right click holds focus, so neither reaches the game. */
@Mixin(Minecraft.class)
public class MinecraftMixin {
	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void framecraft$shutter(CallbackInfoReturnable<Boolean> cir) {
		if (CameraState.isActive()) {
			CameraState.requestCapture();
			((Minecraft) (Object) this).getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.8f));
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void framecraft$noMining(boolean down, CallbackInfo ci) {
		if (CameraState.isActive()) {
			ci.cancel();
		}
	}

	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void framecraft$noUse(CallbackInfo ci) {
		if (CameraState.isActive()) {
			ci.cancel();
		}
	}
}
