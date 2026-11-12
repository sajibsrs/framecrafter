package io.github.sajibsrs.framecrafter.test;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.sajibsrs.framecrafter.CameraState;
import io.github.sajibsrs.framecrafter.CameraState.AfState;
import io.github.sajibsrs.framecrafter.FramecrafterConfig;
import java.io.File;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Builds a small scene with subjects at 3, 8, 20 and 50 m, then drives the camera with real
 * input: back-button AF (right mouse), Ctrl + mouse to move the focus point, and the shutter.
 * Screenshots go to build/run/clientGameTest/screenshots.
 */
public class FramecrafterClientTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getServer().runCommand("time set noon");
			sp.getServer().runCommand("gamerule advance_time false");
			sp.getServer().runCommand("gamerule advance_weather false");
			sp.getServer().runCommand("fill -12 -60 -2 12 -50 70 air");
			sp.getServer().runCommand("fill 0 -60 3 0 -58 3 minecraft:gold_block");
			sp.getServer().runCommand("fill -3 -60 8 -3 -57 8 minecraft:red_wool");
			sp.getServer().runCommand("fill 3 -60 20 4 -55 20 minecraft:blue_wool");
			sp.getServer().runCommand("fill -8 -60 50 8 -52 50 minecraft:oak_planks");
			sp.getServer().runCommand("tp @p 0.5 -60 -1.5 0 8");
			ctx.waitTicks(60);
			ctx.takeScreenshot("framecrafter-0-off");

			ctx.runOnClient(mc -> {
				resetSettings();
				CameraState.toggle();
				CameraState.zoom(0);
				for (int i = 0; i < 30; i++) CameraState.stepAperture(-1);
			});
			expectCentred(ctx, "on entering camera mode");

			// Not holding AF: focus must not move by itself.
			sp.getServer().runCommand("tp @p 0.5 -60 -1.5 0 16");
			ctx.waitTicks(20);
			double idleFocus = ctx.computeOnClient(mc -> CameraState.focusM());
			expect(ctx, AfState.IDLE, "without the AF button");

			// Hold AF on the gold pillar (~4.5 m): lens drives there and the box goes green.
			ctx.getInput().holdKey(o -> o.keyUse);
			ctx.waitTicks(30);
			expect(ctx, AfState.SHARP, "focusing the gold pillar");
			expectFocus(ctx, 4.0, 5.0, "gold pillar");
			ctx.takeScreenshot("framecrafter-1-near-f1.2");
			ctx.runOnClient(mc -> FramecrafterConfig.showCameraInfo = true);
			ctx.waitTicks(2);
			ctx.takeScreenshot("framecrafter-1a-camera-info");
			ctx.runOnClient(mc -> FramecrafterConfig.showCameraInfo = false);
			System.setProperty("framecrafter.debugDepth", "true");
			ctx.waitTicks(2);
			ctx.takeScreenshot("framecrafter-1b-depth-debug");
			System.setProperty("framecrafter.debugDepth", "false");
			ctx.getInput().releaseKey(o -> o.keyUse);
			ctx.waitTicks(2);
			expect(ctx, AfState.IDLE, "after releasing AF");
			if (Math.abs(idleFocus - 5.0) > 0.01) {
				throw new AssertionError("Focus moved without the AF button: " + idleFocus);
			}

			// Ctrl + mouse: slide the focus point right onto the red wool (~9.9 m), then focus there.
			sp.getServer().runCommand("tp @p 0.5 -60 -1.5 2 0");
			ctx.waitTicks(10);
			int width = ctx.computeOnClient(mc -> mc.getWindow().getScreenWidth());
			float yawBefore = ctx.computeOnClient(mc -> mc.player.getYRot());
			ctx.getInput().holdKey(InputConstants.KEY_LCONTROL); // real Ctrl: holdControl() is Cmd on macOS
			// Minecraft ignores the first mouse event of a session; in a real game that has long passed.
			ctx.getInput().moveCursor(0, 0);
			ctx.waitTicks(1);
			ctx.getInput().moveCursor(width * 0.3, 0);
			ctx.waitTicks(2);
			ctx.getInput().releaseKey(InputConstants.KEY_LCONTROL);
			float pointX = ctx.computeOnClient(mc -> CameraState.focusPointX());
			if (pointX < 0.5f) {
				throw new AssertionError("Ctrl + mouse did not move the focus point (x = " + pointX + ")");
			}
			float yaw = ctx.computeOnClient(mc -> mc.player.getYRot());
			if (Math.abs(yaw - yawBefore) > 0.01f) {
				throw new AssertionError("Ctrl + mouse turned the view (yaw " + yawBefore + " -> " + yaw + ")");
			}
			ctx.getInput().holdKey(o -> o.keyUse);
			ctx.waitTicks(30);
			expect(ctx, AfState.SHARP, "focusing the red wool off-centre");
			expectFocus(ctx, 9.0, 11.0, "red wool");
			ctx.takeScreenshot("framecrafter-2-offcentre-red-f1.2");
			ctx.getInput().releaseKey(o -> o.keyUse);

			// Leaving and re-entering camera mode puts the focus point back in the centre.
			ctx.runOnClient(mc -> {
				CameraState.toggle();
				CameraState.toggle();
			});
			expectCentred(ctx, "after re-entering camera mode");

			// Too close for the lens: nose against the pillar, 135 mm can't focus nearer than ~0.9 m.
			sp.getServer().runCommand("tp @p 0.5 -60 2.6 0 0");
			ctx.runOnClient(mc -> CameraState.zoom(13));
			ctx.getInput().holdKey(o -> o.keyUse);
			ctx.waitTicks(30);
			expect(ctx, AfState.FAILED, "a subject closer than the minimum focus distance");
			ctx.takeScreenshot("framecrafter-5-too-close");
			ctx.getInput().releaseKey(o -> o.keyUse);

			// Open sky: nothing to focus on.
			sp.getServer().runCommand("tp @p 0.5 -60 -1.5 0 -70");
			ctx.getInput().holdKey(o -> o.keyUse);
			ctx.waitTicks(10);
			expect(ctx, AfState.FAILED, "open sky");
			ctx.getInput().releaseKey(o -> o.keyUse);

			// Portrait-style: 135 mm wide open on the gold pillar from 9 m back.
			sp.getServer().runCommand("tp @p 0.5 -60 -6.5 0 6");
			ctx.getInput().holdKey(o -> o.keyUse);
			ctx.waitTicks(40);
			expect(ctx, AfState.SHARP, "135 mm on the gold pillar");
			ctx.takeScreenshot("framecrafter-4-135mm-f1.2");
			ctx.runOnClient(mc -> FramecrafterConfig.bokeh = FramecrafterConfig.Bokeh.HEXAGON);
			ctx.waitTicks(2);
			ctx.takeScreenshot("framecrafter-4a-135mm-f1.2-hexagon");
			ctx.runOnClient(mc -> FramecrafterConfig.bokeh = FramecrafterConfig.Bokeh.ROUND);
			ctx.getInput().releaseKey(o -> o.keyUse);

			ctx.runOnClient(mc -> resetSettings());

			// X opens the camera menu in camera mode.
			ctx.getInput().holdKey(InputConstants.KEY_X);
			ctx.waitTicks(2);
			ctx.getInput().releaseKey(InputConstants.KEY_X);
			ctx.waitTicks(5);
			boolean menuOpen = ctx.computeOnClient(mc -> mc.gui.screen() instanceof io.github.sajibsrs.framecrafter.FramecrafterSettingsScreen);
			if (!menuOpen) {
				throw new AssertionError("X did not open the camera menu in camera mode");
			}
			ctx.takeScreenshot("framecrafter-6-menu");
			ctx.runOnClient(mc -> mc.gui.setScreen(null));
			ctx.runOnClient(mc -> CameraState.zoom(-13));

			// Lens and body character: 14 mm with distortion and vignetting, tungsten WB, grain 6.
			ctx.runOnClient(mc -> {
				CameraState.zoom(-30);
				FramecrafterConfig.distortion = true;
				FramecrafterConfig.vignetting = true;
				FramecrafterConfig.whiteBalance = FramecrafterConfig.WhiteBalance.TUNGSTEN;
				FramecrafterConfig.grain = 6;
				FramecrafterConfig.showCameraInfo = true;
			});
			ctx.waitTicks(20);
			ctx.takeScreenshot("framecrafter-7-14mm-tungsten-grain6");
			ctx.runOnClient(mc -> {
				resetSettings();
				CameraState.zoom(Math.log(50.0 / 14.0) / Math.log(1.08)); // back to 50 mm
			});

			// Stop down: almost everything sharp.
			sp.getServer().runCommand("tp @p 0.5 -60 -1.5 0 16");
			ctx.runOnClient(mc -> {
				for (int i = 0; i < 30; i++) CameraState.stepAperture(1);
			});
			ctx.getInput().holdKey(o -> o.keyUse);
			ctx.waitTicks(40);
			ctx.takeScreenshot("framecrafter-3-near-f22");
			ctx.getInput().releaseKey(o -> o.keyUse);

			// Shutter writes a framecrafter_*.png into screenshots/.
			// With Camera Info on, the photo carries the info panel.
			File shots = ctx.computeOnClient(mc -> new File(mc.gameDirectory, "screenshots"));
			int before = countPhotos(shots);
			ctx.runOnClient(mc -> {
				FramecrafterConfig.showCameraInfo = true;
				CameraState.requestCapture();
			});
			ctx.waitTicks(20);
			ctx.runOnClient(mc -> FramecrafterConfig.showCameraInfo = false);
			if (countPhotos(shots) <= before) {
				throw new AssertionError("Shutter did not save a photo in " + shots);
			}
			File[] notes = shots.listFiles((d, n) -> n.startsWith("framecrafter_") && n.endsWith(".txt"));
			if (notes != null && notes.length > 0) {
				throw new AssertionError("Shutter wrote a text file beside the photo in " + shots);
			}

			measureBlur(ctx, sp);
			ctx.runOnClient(mc -> CameraState.toggle());
		}
	}

	/**
	 * Calibration: a black/white edge on a wall 31.5 m away, lens focused at a known distance.
	 * The rendered edge width must match the thin-lens blur circle.
	 */
	private static void measureBlur(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		// Clear the earlier subjects; fill is capped at 32768 blocks per command.
		sp.getServer().runCommand("fill -12 -60 -2 12 -50 29 air");
		sp.getServer().runCommand("fill -30 -61 -2 30 -61 30 minecraft:stone");
		// Facing +z, screen right is -x. The edge is the plane x = -3, 3.5 m right of the eye.
		sp.getServer().runCommand("fill -3 -60 30 30 -40 30 minecraft:white_concrete");
		sp.getServer().runCommand("fill -30 -60 30 -4 -40 30 minecraft:black_concrete");
		ctx.runOnClient(mc -> mc.gui.hud.toggle()); // F1: no viewfinder lines over the edge
		double tolerance = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("iris") ? 0.2 : 0.1;

		double[][] cases = {
			// focal mm, f-number, focus m, eye x, eye z. The wall face is at z = 30, the edge at x = -3.
			{50, 1.2, 2.0, 0.5, -1.5}, // background: wall 31.5 m behind a 2 m focus
			{50, 4.0, 2.0, 0.5, -1.5},
			{50, 1.2, 8.0, 0.5, -1.5},
			{85, 1.2, 2.0, 0.5, -1.5},
			{50, 1.2, 60.0, -2.0, 27.5}, // foreground: wall 2.5 m in front of a 60 m focus
			{85, 1.2, 30.0, -2.5, 26.0},
		};
		for (double[] c : cases) {
			double wallM = 30.0 - c[4];
			double edgeOffsetM = c[3] + 3.0;
			sp.getServer().runCommand(String.format(java.util.Locale.ROOT, "tp @p %.2f -60 %.2f 0 0", c[3], c[4]));
			ctx.runOnClient(mc -> setLens(c[0], (float) c[1], c[2]));
			ctx.waitTicks(20);
			double expected = ctx.computeOnClient(mc ->
				CameraState.blurRadiusPx(wallM, c[2], mc.gameRenderer.mainRenderTarget().height));
			double tanV = ctx.computeOnClient(mc -> Math.tan(Math.toRadians(CameraState.verticalFovDeg()) / 2.0));
			java.nio.file.Path png = ctx.takeScreenshot(String.format(java.util.Locale.ROOT,
				"framecrafter-cal-%dmm-f%.1f-s%.0f-at%.1f", (int) c[0], c[1], c[2], wallM));
			double measured = edgeRadius(png, edgeOffsetM / wallM / tanV, expected);
			System.out.printf(java.util.Locale.ROOT, "[framecrafter-cal] %dmm f/%.1f focus %.1f m, wall %.1f m: expected R %.2f px, measured %.2f px%n",
				(int) c[0], c[1], c[2], wallM, expected, measured);
			// About a pixel of the edge's own antialiasing remains at small radii. A shader pack's
			// tone curve and bloom reshape the edge profile, so allow more there.
			if (Math.abs(measured - expected) > Math.max(0.6, tolerance * expected)) {
				throw new AssertionError(String.format(java.util.Locale.ROOT,
					"Blur at %dmm f/%.1f focus %.1f m: %.2f px, thin lens says %.2f px",
					(int) c[0], c[1], c[2], measured, expected));
			}
		}

		// A mob in the foreground: blocks blur, does an entity? Pillager 3 m out, lens focused at 30 m.
		sp.getServer().runCommand("tp @p 0.5 -60 -1.5 0 0");
		sp.getServer().runCommand("summon minecraft:pillager 0.5 -60 1.5 {NoAI:1b,Rotation:[180f,0f]}");
		ctx.runOnClient(mc -> setLens(85, 1.2f, 3.0));
		ctx.waitTicks(20);
		ctx.takeScreenshot("framecrafter-entity-focused-3m");
		ctx.runOnClient(mc -> setLens(85, 1.2f, 30.0));
		ctx.waitTicks(20);
		ctx.takeScreenshot("framecrafter-entity-foreground-focus30");
		System.setProperty("framecrafter.debugDepth", "true");
		ctx.waitTicks(2);
		ctx.takeScreenshot("framecrafter-entity-foreground-depth");
		System.setProperty("framecrafter.debugDepth", "false");
		sp.getServer().runCommand("kill @e[type=minecraft:pillager]");

		// Bokeh shapes: wall torches facing the lens are small bright points on a black wall
		// 31.5 m away, far smaller than their blur, so each spreads into the aperture's shape.
		// At night, so the flames are the brightest points, as lamps are in a real
		// night shot. The wait lets the pillager's death smoke clear from the foreground.
		sp.getServer().runCommand("time set midnight");
		sp.getServer().runCommand("fill -8 -61 30 9 -50 30 minecraft:black_concrete");
		int[][] rods = {{0, -58}, {-2, -56}, {3, -56}, {-3, -59}, {2, -60}, {5, -58}, {-5, -57}};
		for (int[] r : rods) {
			sp.getServer().runCommand("setblock " + r[0] + " " + r[1] + " 29 minecraft:wall_torch[facing=north]");
		}
		ctx.runOnClient(mc -> setLens(85, 1.2f, 31.5));
		ctx.waitTicks(60);
		ctx.takeScreenshot("framecrafter-bokeh-sharp");
		ctx.runOnClient(mc -> setLens(85, 1.2f, 1.5));
		for (FramecrafterConfig.Bokeh shape : FramecrafterConfig.Bokeh.values()) {
			ctx.runOnClient(mc -> FramecrafterConfig.bokeh = shape);
			ctx.waitTicks(10);
			ctx.takeScreenshot("framecrafter-bokeh-" + shape.name().toLowerCase(java.util.Locale.ROOT));
		}
		// A bright subject in focus is not a bokeh source: no glow may spread from it onto
		// the blurred wall behind.
		sp.getServer().runCommand("fill 0 -60 2 0 -57 2 minecraft:glowstone");
		ctx.runOnClient(mc -> setLens(85, 1.2f, 3.5));
		ctx.waitTicks(10);
		ctx.takeScreenshot("framecrafter-bokeh-subject-in-focus");
		sp.getServer().runCommand("fill 0 -60 2 0 -57 2 air");
		ctx.runOnClient(mc -> FramecrafterConfig.bokeh = FramecrafterConfig.Bokeh.ROUND);
		sp.getServer().runCommand("time set noon");

		// Leaves against the sky, out of focus: the sky gaps must blur like the sky, not
		// swell into white blobs (the sky is not a bokeh source).
		for (int x = -5; x <= 5; x++) {
			for (int y = -56; y <= -49; y++) {
				if (((x * 7 + y * 3) & 3) != 0) {
					sp.getServer().runCommand("setblock " + x + " " + y + " 10 minecraft:oak_leaves[persistent=true]");
				}
			}
		}
		sp.getServer().runCommand("tp @p 0.5 -60 -1.5 0 -25");
		ctx.runOnClient(mc -> setLens(85, 1.2f, 1.5));
		ctx.waitTicks(20);
		ctx.takeScreenshot("framecrafter-bokeh-leaves-sky");
		sp.getServer().runCommand("fill -5 -56 10 5 -49 10 air");
		ctx.runOnClient(mc -> mc.gui.hud.toggle());
	}

	/** Neutral body and lens, so the calibration sees the DOF alone. */
	private static void resetSettings() {
		FramecrafterConfig.isoIndex = FramecrafterConfig.BASE_ISO_INDEX;
		FramecrafterConfig.grain = 0;
		FramecrafterConfig.whiteBalance = FramecrafterConfig.WhiteBalance.CUSTOM;
		FramecrafterConfig.customKelvin = 5500;
		FramecrafterConfig.tint = 0;
		FramecrafterConfig.pictureStyle = FramecrafterConfig.PictureStyle.STANDARD;
		FramecrafterConfig.vignetting = false;
		FramecrafterConfig.distortion = false;
		FramecrafterConfig.showCameraInfo = false;
		FramecrafterConfig.showGrid = true;
		FramecrafterConfig.showHints = true;
	}

	private static void setLens(double focalMm, float fNumber, double focusM) {
		try {
			var cs = CameraState.class;
			for (String f : new String[] {"focalMm", "shownFocalMm"}) {
				var field = cs.getDeclaredField(f);
				field.setAccessible(true);
				field.setFloat(null, (float) focalMm);
			}
			var idx = cs.getDeclaredField("apertureIndex");
			idx.setAccessible(true);
			for (int i = 0; i < CameraState.F_STOPS.length; i++) {
				if (Math.abs(CameraState.F_STOPS[i] - fNumber) < 1e-3) idx.setInt(null, i);
			}
			for (String f : new String[] {"focusBlocks", "targetFocusBlocks"}) {
				var field = cs.getDeclaredField(f);
				field.setAccessible(true);
				field.setDouble(null, focusM);
			}
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Blur radius from the edge's 10–90% fall, averaged over rows near the middle.
	 * {@code edgeNdcY} is the edge's offset from centre in units of the half frame height.
	 * For a uniform disc of radius R the 10–90% width is 1.374 R.
	 */
	private static double edgeRadius(java.nio.file.Path png, double edgeNdcY, double expectedR) {
		try {
			var img = javax.imageio.ImageIO.read(png.toFile());
			int w = img.getWidth();
			int h = img.getHeight();
			int edgeX = (int) Math.round(w / 2.0 + edgeNdcY * h / 2.0);
			int half = (int) Math.ceil(2.5 * expectedR) + 12;
			int x0 = Math.max(0, edgeX - half);
			int x1 = Math.min(w, edgeX + half);
			double sum = 0;
			int rows = 0;
			for (int y = h / 2 - 20; y <= h / 2 + 20; y += 4) {
				double[] lum = new double[x1 - x0];
				for (int x = x0; x < x1; x++) {
					int rgb = img.getRGB(x, y);
					lum[x - x0] = 0.2126 * ((rgb >> 16) & 255) + 0.7152 * ((rgb >> 8) & 255) + 0.0722 * (rgb & 255);
				}
				double hi = lum[0];
				double lo = lum[lum.length - 1];
				double a = cross(lum, lo + 0.9 * (hi - lo));
				double b = cross(lum, lo + 0.1 * (hi - lo));
				sum += (b - a) / 1.374;
				rows++;
			}
			return sum / rows;
		} catch (java.io.IOException e) {
			throw new RuntimeException(e);
		}
	}

	/** First position where a falling profile drops below {@code t}, with sub-pixel interpolation. */
	private static double cross(double[] p, double t) {
		for (int i = 1; i < p.length; i++) {
			if (p[i] < t) {
				return i - 1 + (p[i - 1] - t) / (p[i - 1] - p[i]);
			}
		}
		return p.length;
	}

	private static void expect(ClientGameTestContext ctx, AfState want, String when) {
		AfState got = ctx.computeOnClient(mc -> CameraState.afState());
		if (got != want) {
			double focus = ctx.computeOnClient(mc -> CameraState.focusM());
			throw new AssertionError("AF state " + got + " (focus " + focus + " m), expected " + want + " for " + when);
		}
	}

	private static void expectFocus(ClientGameTestContext ctx, double min, double max, String subject) {
		double focus = ctx.computeOnClient(mc -> CameraState.focusM());
		if (focus < min || focus > max) {
			throw new AssertionError("Focused at " + focus + " m, expected " + min + "–" + max + " m for the " + subject);
		}
	}

	private static void expectCentred(ClientGameTestContext ctx, String when) {
		float x = ctx.computeOnClient(mc -> CameraState.focusPointX());
		float y = ctx.computeOnClient(mc -> CameraState.focusPointY());
		if (x != 0f || y != 0f) {
			throw new AssertionError("Focus point at (" + x + ", " + y + ") " + when + ", expected the centre");
		}
	}

	private static int countPhotos(File dir) {
		File[] files = dir.listFiles((d, name) -> name.startsWith("framecrafter_") && name.endsWith(".png"));
		return files == null ? 0 : files.length;
	}
}
