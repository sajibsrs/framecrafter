package io.github.sajibsrs.framecrafter.mixin;

import io.github.sajibsrs.framecrafter.CameraState;
import io.github.sajibsrs.framecrafter.Viewfinder;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Camera mode swaps the HUD (and the hand, via isHudHidden) for the viewfinder. */
@Mixin(Hud.class)
public class HudMixin {
	@Shadow @Final private Minecraft minecraft;
	/** Vanilla's F1 state; isHidden() itself is forced true in camera mode below. */
	@Shadow private boolean isHidden;

	@Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
	private void framecrafter$viewfinder(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
		if (!CameraState.isActive()) {
			return;
		}
		this.minecraft.gameRenderer.gameRenderState().guiRenderState.isHudHidden = true;
		if (CameraState.isCapturing()) {
			// A photo carries no viewfinder marks, only the camera info when it is switched on.
			Viewfinder.drawPhotoInfo(this.minecraft, graphics);
		} else if (!this.isHidden) {
			// F1 hides the viewfinder too, for a clean live view.
			Viewfinder.draw(this.minecraft, graphics);
		}
		ci.cancel();
	}

	/**
	 * Report the HUD as hidden in camera mode, like F1. Iris checks this before drawing the
	 * hand, and vanilla before drawing the block outline; neither belongs in a photo.
	 */
	@Inject(method = "isHidden", at = @At("HEAD"), cancellable = true)
	private void framecrafter$hiddenInCameraMode(CallbackInfoReturnable<Boolean> cir) {
		if (CameraState.isActive()) {
			cir.setReturnValue(true);
		}
	}
}
