package io.github.sajibsrs.framecraft.mixin;

import io.github.sajibsrs.framecraft.CameraState;
import io.github.sajibsrs.framecraft.SceneDepth;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Snapshot depth right after the world's frame graph runs. Iris runs its composite and
 * final passes at this same point; the lower priority puts this copy before them.
 */
@Mixin(value = LevelRenderer.class, priority = 900)
public class LevelRendererMixin {
	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lorg/joml/Matrix4fStack;popMatrix()Lorg/joml/Matrix4fStack;"))
	private void framecraft$captureDepth(CallbackInfo ci) {
		if (CameraState.isActive()) {
			SceneDepth.capture(Minecraft.getInstance());
		}
	}
}
