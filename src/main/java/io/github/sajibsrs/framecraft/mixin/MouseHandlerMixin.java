package io.github.sajibsrs.framecraft.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.sajibsrs.framecraft.CameraState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Scroll zooms; Ctrl+scroll changes aperture; Ctrl+move slides the focus point. The hotbar stays put in camera mode. */
@Mixin(MouseHandler.class)
public class MouseHandlerMixin {
	@Shadow @Final private Minecraft minecraft;
	@Shadow private double accumulatedDX;
	@Shadow private double accumulatedDY;

	/** Ctrl + mouse move slides the focus point instead of turning the view. */
	@Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
	private void framecraft$moveFocusPoint(double mousea, CallbackInfo ci) {
		if (!CameraState.isActive() || this.minecraft.gui.screen() != null || !framecraft$ctrlDown()) {
			return;
		}
		CameraState.moveFocusPoint(this.accumulatedDX, this.accumulatedDY,
			this.minecraft.getWindow().getScreenWidth(), this.minecraft.getWindow().getScreenHeight());
		ci.cancel();
	}

	private static boolean framecraft$ctrlDown() {
		return InputConstants.isKeyDown(InputConstants.KEY_LCONTROL) || InputConstants.isKeyDown(InputConstants.KEY_RCONTROL);
	}

	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void framecraft$lensControls(long handle, double xoffset, double yoffset, CallbackInfo ci) {
		if (!CameraState.isActive() || this.minecraft.gui.screen() != null || this.minecraft.player == null) {
			return;
		}
		if (handle == 0L || handle != this.minecraft.getWindow().handle() || yoffset == 0.0) {
			return;
		}
		if (framecraft$ctrlDown()) {
			// Scroll up opens the aperture (smaller f-number), like turning toward wide open.
			CameraState.stepAperture(yoffset > 0 ? -1 : 1);
		} else {
			CameraState.zoom(yoffset);
		}
		ci.cancel();
	}
}
