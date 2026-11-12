package io.github.sajibsrs.framecrafter.mixin;

import io.github.sajibsrs.framecrafter.CameraState;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Focal length decides the field of view while in camera mode. */
@Mixin(Camera.class)
public class CameraMixin {
	@Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
	private void framecrafter$lensFov(float partialTicks, CallbackInfoReturnable<Float> cir) {
		if (CameraState.isActive()) {
			cir.setReturnValue(CameraState.verticalFovDeg());
		}
	}
}
