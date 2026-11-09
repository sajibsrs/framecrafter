package io.github.sajibsrs.framecraft;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import io.github.sajibsrs.framecraft.mixin.PostChainAccessor;
import io.github.sajibsrs.framecraft.mixin.PostPassAccessor;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;

/**
 * Drives the framecraft:dof post chain. Vanilla post-effect uniforms are fixed at load time,
 * so the "DofConfig" buffer is swapped for a writable one and refreshed every frame.
 */
public final class DofEffect {
	public static final Identifier ID = Identifier.fromNamespaceAndPath("framecraft", "dof");
	private static final String BLOCK = "DofConfig";
	private static final int BLOCK_SIZE = 3 * 16; // three vec4

	/** Rough number of gather samples per pixel. The shot itself gets many more. */
	private static final float LIVE_SAMPLES = 96f;
	private static final float CAPTURE_SAMPLES = 640f;
	/** Largest blur radius as a fraction of screen height, so cost stays bounded. */
	private static final float MAX_RADIUS_FRACTION = 0.02f;

	private static final Map<PostPass, GpuBuffer> OWNED = new WeakHashMap<>();

	private DofEffect() {
	}

	/**
	 * DOF runs in camera mode, with or without a shader pack. Iris draws its final pass into the
	 * main target and keeps scene depth in the main depth buffer, so this pass works on top of it.
	 */
	public static boolean shouldRun() {
		return CameraState.isActive();
	}

	public static void update(Minecraft mc, PostChain chain) {
		if (!ID.equals(chain.id())) {
			return;
		}

		int height = mc.gameRenderer.mainRenderTarget().height;
		// Vanilla's projection, even with an Iris pack on: Iris only un-reverses Z for the pack's
		// own draws, and the main depth buffer we snapshot stays reversed-Z (checked in-game).
		// Iris does hide clip control from the device, which isZZeroToOne() below picks up.
		Matrix4fc proj = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.projectionMatrix;
		boolean zeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();

		// Window depth for a perspective projection is d = a + b / z. Solving from two
		// sample distances keeps this right for normal and reversed-Z, any depth range.
		float d1 = windowDepth(proj, 1f, zeroToOne);
		float d2 = windowDepth(proj, 10f, zeroToOne);
		float b = (d1 - d2) / (1f - 0.1f);
		float a = d1 - b;
		float skyDepth = d1 > d2 ? 0f : 1f;

		float focal = CameraState.focalMm() / 1000f;
		float apertureDiameter = focal / CameraState.fNumber();
		float pxPerSensorMeter = height / CameraState.SENSOR_HEIGHT_M;
		float maxRadius = Math.max(4f, height * MAX_RADIUS_FRACTION);
		float samples = CameraState.isCapturing() ? CAPTURE_SAMPLES : LIVE_SAMPLES;
		// The spiral's r^2 grows by 2*radScale per sample, so this gives about `samples` taps.
		float radScale = Math.max(0.5f, maxRadius * maxRadius / (2f * samples));

		for (PostPass pass : ((PostChainAccessor) chain).framecraft$passes()) {
			Map<String, GpuBuffer> uniforms = ((PostPassAccessor) pass).framecraft$customUniforms();
			if (!uniforms.containsKey(BLOCK)) {
				continue;
			}
			GpuBuffer buffer = OWNED.computeIfAbsent(pass, p -> {
				GpuBuffer original = uniforms.get(BLOCK);
				GpuBuffer writable = RenderSystem.getDevice()
					.createBuffer(() -> "framecraft DofConfig", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, BLOCK_SIZE);
				uniforms.put(BLOCK, writable);
				original.close();
				return writable;
			});

			try (MemoryStack stack = MemoryStack.stackPush()) {
				Std140Builder builder = Std140Builder.onStack(stack, BLOCK_SIZE);
				builder.putVec4((float) CameraState.focusM(), apertureDiameter, focal, pxPerSensorMeter);
				builder.putVec4(a, b, maxRadius, radScale);
				builder.putVec4(skyDepth, Boolean.getBoolean("framecraft.debugDepth") ? 1f : 0f, 0f, 0f);
				RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(), builder.get());
			}
		}
	}

	private static float windowDepth(Matrix4fc proj, float viewDistance, boolean zeroToOne) {
		Vector4f clip = proj.transform(new Vector4f(0f, 0f, -viewDistance, 1f));
		float ndc = clip.z / clip.w;
		return zeroToOne ? ndc : ndc * 0.5f + 0.5f;
	}
}
