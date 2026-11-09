package io.github.sajibsrs.framecraft;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

/**
 * Everything the camera knows: lens settings, focus, and capture requests.
 * Lengths are in meters (1 block = 1 m), focal length is shown in mm.
 */
public final class CameraState {
	/** Full-frame sensor: 36 x 24 mm. FOV is vertical in Minecraft, so height is what matters. */
	public static final float SENSOR_HEIGHT_M = 0.024f;

	public static final float MIN_FOCAL_MM = 14f;
	public static final float MAX_FOCAL_MM = 200f;

	/** Third-stop aperture scale, as printed on real lenses. */
	public static final float[] F_STOPS = {
		1.4f, 1.6f, 1.8f, 2f, 2.2f, 2.5f, 2.8f, 3.2f, 3.5f, 4f, 4.5f, 5f, 5.6f,
		6.3f, 7.1f, 8f, 9f, 10f, 11f, 13f, 14f, 16f, 18f, 20f, 22f
	};

	/** Nothing closer than the near clip plane, nothing farther than this counts as "infinity". */
	private static final double MIN_FOCUS_M = 0.1;
	private static final double MAX_FOCUS_M = 1000.0;
	private static final double AF_RANGE_M = 512.0;
	private static final double ENTITY_AF_RANGE_M = 128.0;

	private static boolean active;

	private static float focalMm = 50f;
	private static float shownFocalMm = 50f;
	private static int apertureIndex = 6; // f/2.8

	private static double focusM = 5.0;
	private static double targetFocusM = 5.0;
	private static boolean focusLocked;

	private static boolean captureRequested;
	private static boolean capturing;
	private static long flashUntilMs;

	private static long lastFrameNs;

	private CameraState() {
	}

	public static boolean isActive() {
		return active;
	}

	public static void toggle() {
		active = !active;
		if (active) {
			shownFocalMm = focalMm;
			focusLocked = false;
		}
		captureRequested = false;
		capturing = false;
	}

	public static float focalMm() {
		return shownFocalMm;
	}

	public static float fNumber() {
		return F_STOPS[apertureIndex];
	}

	public static double focusM() {
		return focusM;
	}

	public static boolean isFocusLocked() {
		return focusLocked;
	}

	public static boolean isCapturing() {
		return capturing;
	}

	public static boolean isFlashing() {
		return System.currentTimeMillis() < flashUntilMs;
	}

	/** Vertical field of view for the current focal length: 2 * atan(h / 2f). */
	public static float verticalFovDeg() {
		double halfHeightMm = SENSOR_HEIGHT_M * 1000.0 / 2.0;
		return (float) Math.toDegrees(2.0 * Math.atan(halfHeightMm / shownFocalMm));
	}

	public static void zoom(double steps) {
		// Multiplicative steps feel even across the range, like a zoom ring.
		focalMm = Mth.clamp((float) (focalMm * Math.pow(1.08, steps)), MIN_FOCAL_MM, MAX_FOCAL_MM);
	}

	public static void stepAperture(int stops) {
		apertureIndex = Mth.clamp(apertureIndex + stops, 0, F_STOPS.length - 1);
	}

	public static void requestCapture() {
		captureRequested = true;
	}

	/** Called once at the start of each rendered frame. */
	public static void beginFrame(Minecraft mc) {
		long now = System.nanoTime();
		float dt = lastFrameNs == 0 ? 0f : Math.min((now - lastFrameNs) / 1.0e9f, 0.1f);
		lastFrameNs = now;

		if (!active) {
			return;
		}
		if (mc.player == null || mc.level == null) {
			active = false;
			return;
		}

		// Zoom ring has some inertia so scrolling isn't jumpy.
		float zoomK = 1f - (float) Math.exp(-dt * 14.0);
		shownFocalMm += (focalMm - shownFocalMm) * zoomK;
		if (Math.abs(focalMm - shownFocalMm) < 0.01f) {
			shownFocalMm = focalMm;
		}

		// Hold "use" (right mouse) to lock focus, like back-button AF.
		focusLocked = mc.options.keyUse.isDown() && mc.gui.screen() == null;
		if (!focusLocked) {
			targetFocusM = autofocusDistance(mc);
		}
		// Focus motor moves in log space so near and far pulls take similar time.
		double afK = 1.0 - Math.exp(-dt * 10.0);
		double logF = Math.log(focusM);
		focusM = Math.exp(logF + (Math.log(targetFocusM) - logF) * afK);

		if (captureRequested) {
			captureRequested = false;
			capturing = true;
		}
	}

	/** Called at the end of a frame that was rendered for a capture. */
	public static void endCapture() {
		capturing = false;
		flashUntilMs = System.currentTimeMillis() + 120;
	}

	/** Distance along the view axis to whatever is under the crosshair. */
	private static double autofocusDistance(Minecraft mc) {
		Camera camera = mc.gameRenderer.mainCamera();
		Entity viewer = mc.getCameraEntity();
		if (!camera.isInitialized() || viewer == null) {
			return targetFocusM;
		}

		Vector3fc fwd = camera.forwardVector();
		Vec3 dir = new Vec3(fwd.x(), fwd.y(), fwd.z());
		Vec3 from = camera.position();
		Vec3 to = from.add(dir.scale(AF_RANGE_M));

		double best = MAX_FOCUS_M;
		BlockHitResult block = mc.level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, viewer));
		if (block.getType() != HitResult.Type.MISS) {
			best = block.getLocation().distanceTo(from);
			to = block.getLocation();
		}

		// Entities only within a shorter range: a 512-block search box would be slow.
		Vec3 entityTo = from.add(dir.scale(Math.min(best, ENTITY_AF_RANGE_M)));
		AABB search = viewer.getBoundingBox().expandTowards(entityTo.subtract(from)).inflate(1.0);
		EntityHitResult entity = ProjectileUtil.getEntityHitResult(viewer, from, entityTo, search, e -> !e.isSpectator() && e.isPickable(), best * best);
		if (entity != null) {
			best = Math.min(best, entity.getLocation().distanceTo(from));
		}

		return Mth.clamp(best, MIN_FOCUS_M, MAX_FOCUS_M);
	}
}
