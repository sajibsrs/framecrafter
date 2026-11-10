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
 * World positions are in blocks; the optics work in meters, one block being
 * {@link #SCENE_SCALE} meters. Focal length is shown in mm. The sensor is full frame.
 */
public final class CameraState {
	public static final float MIN_FOCAL_MM = 14f;
	public static final float MAX_FOCAL_MM = 200f;
	/** Full-frame sensor height. FOV is vertical in Minecraft, so height is what matters. */
	public static final float SENSOR_HEIGHT_M = 0.024f;
	/** Meters per block. A player is 1.8 blocks tall, so one block is one meter. */
	public static final double SCENE_SCALE = 1.0;

	/** Third-stop aperture scale, as printed on real lenses. */
	public static final float[] F_STOPS = {
		1.2f, 1.4f, 1.6f, 1.8f, 2f, 2.2f, 2.5f, 2.8f, 3.2f, 3.5f, 4f, 4.5f, 5f, 5.6f,
		6.3f, 7.1f, 8f, 9f, 10f, 11f, 13f, 14f, 16f, 18f, 20f, 22f
	};

	/** Nothing farther than this counts as "infinity". */
	private static final double MAX_FOCUS_M = 1000.0;
	/** A subject counts as sharp when its blur circle is at most this many pixels in radius. */
	public static final double SHARP_RADIUS_PX = 1.0;
	/** How far the focus point may go from the centre, as a fraction of the half-frame. */
	private static final float FOCUS_POINT_LIMIT = 0.9f;
	private static final double AF_RANGE_M = 512.0;
	private static final double ENTITY_AF_RANGE_M = 128.0;

	private static boolean active;

	private static float focalMm = 50f;
	private static float shownFocalMm = 50f;
	private static int apertureIndex = 7; // f/2.8

	/** What the focus box shows. */
	public enum AfState {
		/** AF button not held; focus stays where it was left. */
		IDLE,
		/** Lens is still moving toward the subject. */
		HUNTING,
		/** Subject under the box is in focus. */
		SHARP,
		/** Nothing to focus on (sky), or the subject is closer than the lens can focus. */
		FAILED
	}

	/** Focus distance in blocks. */
	private static double focusBlocks = 5.0;
	private static double targetFocusBlocks = 5.0;
	private static AfState afState = AfState.IDLE;
	/** Depth in blocks of whatever is under the focus point, -1 if nothing; refreshed while AF is held or info is shown. */
	private static double subjectBlocks = -1;

	/** Focus point as an offset from the frame centre, -1..1 on each axis, +y up. */
	private static float focusPointX;
	private static float focusPointY;

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
			afState = AfState.IDLE;
			focusPointX = 0f;
			focusPointY = 0f;
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

	public static float sensorHeightM() {
		return SENSOR_HEIGHT_M;
	}

	public static double sceneScale() {
		return SCENE_SCALE;
	}

	/** Focus distance in scene meters. */
	public static double focusM() {
		return focusBlocks * sceneScale();
	}

	public static AfState afState() {
		return afState;
	}

	public static float focusPointX() {
		return focusPointX;
	}

	public static float focusPointY() {
		return focusPointY;
	}

	/** Moves the focus point by a mouse delta in window pixels, so the box follows the cursor. */
	public static void moveFocusPoint(double dxPx, double dyPx, int windowWidth, int windowHeight) {
		if (windowWidth <= 0 || windowHeight <= 0) {
			return;
		}
		focusPointX = Mth.clamp(focusPointX + (float) (2.0 * dxPx / windowWidth), -FOCUS_POINT_LIMIT, FOCUS_POINT_LIMIT);
		focusPointY = Mth.clamp(focusPointY - (float) (2.0 * dyPx / windowHeight), -FOCUS_POINT_LIMIT, FOCUS_POINT_LIMIT);
	}

	/**
	 * Closest distance the lens can focus. Roughly what real lenses manage:
	 * about 0.27 m at 14 mm, 0.45 m at 50 mm, 1.2 m at 200 mm.
	 */
	public static double minFocusM() {
		return 0.2 + 5.0 * shownFocalMm / 1000.0;
	}

	/** Blur radius in pixels of something at depth {@code z} with the lens focused at {@code s}, both in scene meters. Same formula as the shader. */
	public static double blurRadiusPx(double z, double s, int frameHeightPx) {
		double f = shownFocalMm / 1000.0;
		double aperture = f / fNumber();
		double coc = aperture * f * Math.abs(z - s) / (z * Math.max(s - f, 1e-4));
		return 0.5 * coc * frameHeightPx / sensorHeightM();
	}

	/** Subject distance in scene meters, or -1 if nothing is under the focus point. */
	public static double subjectM() {
		return subjectBlocks > 0 ? subjectBlocks * sceneScale() : -1;
	}

	/**
	 * Radial distortion coefficient: positive is barrel, negative pincushion. Roughly what real
	 * zooms show uncorrected: strong barrel at 14 mm, none near 50 mm, mild pincushion at 200 mm.
	 */
	public static float distortionK() {
		if (!FramecraftConfig.distortion) {
			return 0f;
		}
		double f = shownFocalMm;
		if (f <= 50.0) {
			return (float) (0.06 * Math.log(50.0 / f) / Math.log(50.0 / 14.0));
		}
		return (float) (-0.02 * Math.min(1.0, Math.log(f / 50.0) / Math.log(4.0)));
	}

	/**
	 * Light falloff at the frame corner, in stops. Optical vignetting is strong wide open and
	 * gone about three stops down; natural (cos^4) falloff grows on wide lenses.
	 */
	public static float vignetteEv() {
		if (!FramecraftConfig.vignetting) {
			return 0f;
		}
		double stopsDown = 2.0 * Math.log(fNumber() / F_STOPS[0]) / Math.log(2.0);
		double optical = 1.2 * Math.max(0.0, 1.0 - stopsDown / 3.0);
		double natural = 0.1 + 0.3 * Math.clamp(Math.log(200.0 / shownFocalMm) / Math.log(200.0 / 14.0), 0.0, 1.0);
		return (float) (optical + natural);
	}

	/**
	 * Hyperfocal distance for a blur limit of {@code radiusPx}, derived from {@link #blurRadiusPx}
	 * so it agrees with the shader: focus here and everything from H/2 to infinity is sharp.
	 */
	public static double hyperfocalM(double radiusPx, int frameHeightPx) {
		double f = shownFocalMm / 1000.0;
		double c = cocDiameterM(radiusPx, frameHeightPx);
		return f * f / (fNumber() * c) + f;
	}

	/** Nearest sharp depth with the lens focused at {@code s}. */
	public static double dofNearM(double s, double radiusPx, int frameHeightPx) {
		double f = shownFocalMm / 1000.0;
		double h = hyperfocalM(radiusPx, frameHeightPx);
		return s * (h - f) / (h + s - 2 * f);
	}

	/** Farthest sharp depth with the lens focused at {@code s}, or infinity at or past the hyperfocal distance. */
	public static double dofFarM(double s, double radiusPx, int frameHeightPx) {
		double f = shownFocalMm / 1000.0;
		double h = hyperfocalM(radiusPx, frameHeightPx);
		return s >= h ? Double.POSITIVE_INFINITY : s * (h - f) / (h - s);
	}

	/** Blur-circle diameter on the sensor, in meters, that maps to {@code radiusPx} on screen. */
	public static double cocDiameterM(double radiusPx, int frameHeightPx) {
		return 2.0 * radiusPx * sensorHeightM() / Math.max(1, frameHeightPx);
	}

	public static boolean isCapturing() {
		return capturing;
	}

	public static boolean isFlashing() {
		return System.currentTimeMillis() < flashUntilMs;
	}

	/** Vertical field of view for the current focal length: 2 * atan(h / 2f). */
	public static float verticalFovDeg() {
		double halfHeightMm = sensorHeightM() * 1000.0 / 2.0;
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

		// Back-button AF: focus only moves while "use" (right mouse) is held.
		// Let go and it stays where it is, as with AF-ON on a real camera.
		boolean afHeld = mc.options.keyUse.isDown() && mc.gui.screen() == null;
		if (afHeld || FramecraftConfig.showCameraInfo) {
			subjectBlocks = subjectDepth(mc);
		}
		if (afHeld) {
			double subject = subjectBlocks;
			double minFocusBlocks = minFocusM() / sceneScale();
			if (subject > 0) {
				// The lens can't go nearer than its minimum; the subject then stays soft.
				targetFocusBlocks = Math.max(subject, minFocusBlocks);
			}
			// Focus motor moves in log space so near and far pulls take similar time.
			double afK = 1.0 - Math.exp(-dt * 10.0);
			double logF = Math.log(focusBlocks);
			focusBlocks = Math.exp(logF + (Math.log(targetFocusBlocks) - logF) * afK);

			if (subject <= 0 || subject < minFocusBlocks) {
				afState = AfState.FAILED;
			} else if (blurRadiusPx(subject * sceneScale(), focusM(), mc.gameRenderer.mainRenderTarget().height) <= SHARP_RADIUS_PX) {
				afState = AfState.SHARP;
			} else {
				afState = AfState.HUNTING;
			}
		} else {
			afState = AfState.IDLE;
		}

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

	/**
	 * Depth along the view axis of whatever is under the focus point, or -1 if there is
	 * nothing in range (open sky). View-axis depth, not ray length, is what the DOF shader
	 * compares against, so an off-centre subject lands exactly in focus.
	 */
	private static double subjectDepth(Minecraft mc) {
		Camera camera = mc.gameRenderer.mainCamera();
		Entity viewer = mc.getCameraEntity();
		if (!camera.isInitialized() || viewer == null) {
			return -1;
		}

		// Aim the ray through the focus point: offset the view axis by the frame's half-extents.
		Vector3fc fwd = camera.forwardVector();
		Vector3fc up = camera.upVector();
		Vector3fc left = camera.leftVector();
		double tanV = Math.tan(Math.toRadians(verticalFovDeg()) / 2.0);
		double tanH = tanV * mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight());
		double sx = -focusPointX * tanH; // "left" is negative x on screen
		double sy = focusPointY * tanV;
		Vec3 axis = new Vec3(fwd.x(), fwd.y(), fwd.z());
		Vec3 dir = new Vec3(
			fwd.x() + left.x() * sx + up.x() * sy,
			fwd.y() + left.y() * sx + up.y() * sy,
			fwd.z() + left.z() * sx + up.z() * sy).normalize();

		Vec3 from = camera.position();
		Vec3 to = from.add(dir.scale(AF_RANGE_M));

		Vec3 hit = null;
		double bestSq = Double.MAX_VALUE;
		BlockHitResult block = mc.level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, viewer));
		if (block.getType() != HitResult.Type.MISS) {
			hit = block.getLocation();
			bestSq = hit.distanceToSqr(from);
		}

		// Entities only within a shorter range: a 512-block search box would be slow.
		double entityRange = Math.min(Math.sqrt(Math.min(bestSq, AF_RANGE_M * AF_RANGE_M)), ENTITY_AF_RANGE_M);
		Vec3 entityTo = from.add(dir.scale(entityRange));
		AABB search = viewer.getBoundingBox().expandTowards(entityTo.subtract(from)).inflate(1.0);
		EntityHitResult entity = ProjectileUtil.getEntityHitResult(viewer, from, entityTo, search,
			e -> !e.isSpectator() && e.isPickable(), entityRange * entityRange);
		if (entity != null && entity.getLocation().distanceToSqr(from) < bestSq) {
			hit = entity.getLocation();
		}

		if (hit == null) {
			return -1;
		}
		return Math.min(hit.subtract(from).dot(axis), MAX_FOCUS_M);
	}
}
