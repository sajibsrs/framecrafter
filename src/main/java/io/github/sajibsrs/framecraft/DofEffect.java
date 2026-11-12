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
 * Drives the framecraft:dof post chain: two highlight-spread passes that feed the bokeh, the
 * depth-of-field pass, a smoothing pass that removes the gather's grain, then the grade pass (lens
 * distortion, vignetting, exposure, white balance, picture style, grain). Vanilla
 * post-effect uniforms are fixed at load time, so each pass's config buffer is swapped for a
 * writable one and refreshed every frame.
 */
public final class DofEffect {
	public static final Identifier ID = Identifier.fromNamespaceAndPath("framecraft", "dof");
	private static final String BLOCK = "DofConfig";
	private static final int BLOCK_SIZE = 3 * 16; // three vec4
	private static final String GRADE_BLOCK = "GradeConfig";
	private static final int GRADE_BLOCK_SIZE = 4 * 16; // four vec4

	/**
	 * Gather taps are spaced this far apart along the radius (pixels). The shader spreads
	 * them evenly in radius, so the count grows only linearly with the blur size.
	 */
	private static final float LIVE_STEP_PX = 0.75f;
	private static final float CAPTURE_STEP_PX = 0.15f;
	/**
	 * Largest blur radius as a fraction of frame height. 85 mm f/1.2 focused at 2 m puts the
	 * background at about 6.5%, 135 mm f/1.2 at 2 m about 16%, so 20% covers real lenses.
	 */
	private static final float MAX_RADIUS_FRACTION = 0.20f;
	/** Nearest depth worth blurring for; the camera's near plane is closer, but nothing sits there. */
	private static final double NEAREST_SUBJECT_M = 0.3;

	private static final Map<PostPass, GpuBuffer> OWNED = new WeakHashMap<>();

	/** Last values sent to the shader, for the camera info readout. */
	private static float lastMaxRadiusPx;
	private static float lastSampleCount;
	private static int frame;

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
		// d = a + b/z with z in blocks; scaling b gives z in scene meters, which the lens works in.
		float bMeters = b * (float) CameraState.sceneScale();
		float skyDepth = d1 > d2 ? 0f : 1f;

		float focal = CameraState.focalMm() / 1000f;
		float apertureDiameter = focal / CameraState.fNumber();
		float pxPerSensorMeter = height / CameraState.sensorHeightM();
		double focusM = CameraState.focusM();
		float maxRadius = maxRadiusPx(height);
		float sampleCount = sampleCount(height, CameraState.isCapturing());
		lastMaxRadiusPx = maxRadius;
		lastSampleCount = sampleCount;
		boolean debugDepth = Boolean.getBoolean("framecraft.debugDepth");
		frame = (frame + 1) & 0xFFFF;

		for (PostPass pass : ((PostChainAccessor) chain).framecraft$passes()) {
			GpuBuffer dof = writable(pass, BLOCK, BLOCK_SIZE);
			if (dof != null) {
				try (MemoryStack stack = MemoryStack.stackPush()) {
					Std140Builder builder = Std140Builder.onStack(stack, BLOCK_SIZE);
					builder.putVec4((float) focusM, apertureDiameter, focal, pxPerSensorMeter);
					builder.putVec4(a, bMeters, maxRadius, sampleCount);
					builder.putVec4(skyDepth, debugDepth ? 1f : 0f, FramecraftConfig.bokeh.blades, 0f);
					RenderSystem.getDevice().createCommandEncoder().writeToBuffer(dof.slice(), builder.get());
				}
			}
			GpuBuffer grade = writable(pass, GRADE_BLOCK, GRADE_BLOCK_SIZE);
			if (grade != null) {
				int width = mc.gameRenderer.mainRenderTarget().width;
				float[] wb = whiteBalanceGain(FramecraftConfig.kelvin(), FramecraftConfig.effectiveTint());
				FramecraftConfig.PictureStyle style = FramecraftConfig.pictureStyle;
				float grain = 0.01f * FramecraftConfig.grain;
				try (MemoryStack stack = MemoryStack.stackPush()) {
					Std140Builder builder = Std140Builder.onStack(stack, GRADE_BLOCK_SIZE);
					builder.putVec4((float) Math.pow(2.0, FramecraftConfig.exposureEv()), grain, style.contrast, style.saturation);
					builder.putVec4(wb[0], wb[1], wb[2], 0f);
					builder.putVec4(CameraState.distortionK(), CameraState.vignetteEv(), frame, (float) width / Math.max(1, height));
					builder.putVec4(debugDepth ? 1f : 0f, 0f, 0f, 0f);
					RenderSystem.getDevice().createCommandEncoder().writeToBuffer(grade.slice(), builder.get());
				}
			}
		}
	}

	/** The pass's writable copy of {@code block}, made on first use; null if the pass has no such block. */
	private static GpuBuffer writable(PostPass pass, String block, int size) {
		Map<String, GpuBuffer> uniforms = ((PostPassAccessor) pass).framecraft$customUniforms();
		if (!uniforms.containsKey(block)) {
			return null;
		}
		return OWNED.computeIfAbsent(pass, p -> {
			GpuBuffer original = uniforms.get(block);
			GpuBuffer copy = RenderSystem.getDevice()
				.createBuffer(() -> "framecraft " + block, GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, size);
			uniforms.put(block, copy);
			original.close();
			return copy;
		});
	}

	/**
	 * Linear RGB gains for a camera white balance of {@code kelvin} under daylight (5500 K):
	 * daylight's colour divided by the colour the camera expects, so a tungsten setting turns
	 * daylight blue, as on a real camera. Tint moves green against magenta. Luminance is kept.
	 */
	static float[] whiteBalanceGain(int kelvin, int tint) {
		double[] day = blackbody(5500);
		double[] set = blackbody(kelvin);
		double r = day[0] / set[0];
		double g = day[1] / set[1] * Math.exp(-0.02 * tint);
		double b = day[2] / set[2];
		double lum = 0.2126 * r + 0.7152 * g + 0.0722 * b;
		return new float[] {(float) (r / lum), (float) (g / lum), (float) (b / lum)};
	}

	/** Blackbody colour in linear RGB (Tanner Helland's fit to the Planckian locus, 1000–40000 K). */
	private static double[] blackbody(int kelvin) {
		double t = kelvin / 100.0;
		double r = t <= 66 ? 255 : 329.698727446 * Math.pow(t - 60, -0.1332047592);
		double g = t <= 66 ? 99.4708025861 * Math.log(t) - 161.1195681661 : 288.1221695283 * Math.pow(t - 60, -0.0755148492);
		double b = t >= 66 ? 255 : t <= 19 ? 0 : 138.5177312231 * Math.log(t - 10) - 305.0447927307;
		return new double[] {linear(r), linear(g), linear(b)};
	}

	private static double linear(double c255) {
		return Math.pow(Math.clamp(c255, 1.0, 255.0) / 255.0, 2.2);
	}

	/**
	 * Search only as far as the biggest blur this lens can make right now (infinity or the
	 * nearest subject), so stopped down the samples pack tight and cost drops.
	 */
	public static float maxRadiusPx(int height) {
		double focusM = CameraState.focusM();
		double lensMax = Math.max(
			CameraState.blurRadiusPx(1.0e5, focusM, height),
			CameraState.blurRadiusPx(Math.min(NEAREST_SUBJECT_M, focusM), focusM, height));
		return (float) Math.clamp(lensMax, 2.0, height * MAX_RADIUS_FRACTION);
	}

	/** Gather taps per pixel; a capture uses finer spacing than the live view. */
	public static float sampleCount(int height, boolean capture) {
		float step = capture ? CAPTURE_STEP_PX : LIVE_STEP_PX;
		return Math.max(16f, (float) Math.ceil(maxRadiusPx(height) / step));
	}

	public static float lastMaxRadiusPx() {
		return lastMaxRadiusPx;
	}

	public static float lastSampleCount() {
		return lastSampleCount;
	}

	private static float windowDepth(Matrix4fc proj, float viewDistance, boolean zeroToOne) {
		Vector4f clip = proj.transform(new Vector4f(0f, 0f, -viewDistance, 1f));
		float ndc = clip.z / clip.w;
		return zeroToOne ? ndc : ndc * 0.5f + 0.5f;
	}
}
