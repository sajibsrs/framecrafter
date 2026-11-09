package io.github.sajibsrs.framecraft.mixin;

import io.github.sajibsrs.framecraft.CameraState;
import io.github.sajibsrs.framecraft.DofEffect;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.PostChain;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Shadow @Final private Minecraft minecraft;

	private static final DateTimeFormatter FRAMECRAFT$NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss.SSS");

	@Inject(method = "update", at = @At("HEAD"))
	private void framecraft$beginFrame(DeltaTracker deltaTracker, CallbackInfo ci) {
		CameraState.beginFrame(this.minecraft);
	}

	/** Ask for the DOF chain like vanilla asks for spectator effects. */
	@Inject(method = "update", at = @At("TAIL"))
	private void framecraft$requestDof(DeltaTracker deltaTracker, CallbackInfo ci) {
		if (DofEffect.shouldRun()) {
			((GameRendererAccessor) this).framecraft$requestedPostEffects().add(DofEffect.ID);
		}
	}

	@Inject(method = "applyPostEffects", at = @At("HEAD"))
	private void framecraft$updateDof(CallbackInfo ci) {
		for (PostChain chain : ((GameRendererAccessor) this).framecraft$appliedPostEffects()) {
			DofEffect.update(this.minecraft, chain);
		}
	}

	/** The viewfinder was skipped this frame, so the main target holds a clean photo. */
	@Inject(method = "render", at = @At("TAIL"))
	private void framecraft$capture(CallbackInfo ci) {
		if (!CameraState.isCapturing()) {
			return;
		}
		CameraState.endCapture();
		String name = "framecraft_" + LocalDateTime.now().format(FRAMECRAFT$NAME) + ".png";
		Screenshot.grab(this.minecraft.gameDirectory, name, ((GameRenderer) (Object) this).mainRenderTarget(), 1,
			message -> this.minecraft.execute(() -> this.minecraft.showDebugChat(message)));
	}
}
