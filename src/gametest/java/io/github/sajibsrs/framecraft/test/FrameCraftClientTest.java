package io.github.sajibsrs.framecraft.test;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.sajibsrs.framecraft.CameraState;
import io.github.sajibsrs.framecraft.CameraState.AfState;
import java.io.File;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Builds a small scene with subjects at 3, 8, 20 and 50 m, then drives the camera with real
 * input: back-button AF (right mouse), Ctrl + mouse to move the focus point, and the shutter.
 * Screenshots go to build/run/clientGameTest/screenshots.
 */
public class FrameCraftClientTest implements FabricClientGameTest {
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
			ctx.takeScreenshot("framecraft-0-off");

			ctx.runOnClient(mc -> {
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
			ctx.takeScreenshot("framecraft-1-near-f1.4");
			System.setProperty("framecraft.debugDepth", "true");
			ctx.waitTicks(2);
			ctx.takeScreenshot("framecraft-1b-depth-debug");
			System.setProperty("framecraft.debugDepth", "false");
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
			ctx.takeScreenshot("framecraft-2-offcentre-red-f1.4");
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
			ctx.takeScreenshot("framecraft-5-too-close");
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
			ctx.takeScreenshot("framecraft-4-135mm-f1.4");
			ctx.getInput().releaseKey(o -> o.keyUse);
			ctx.runOnClient(mc -> CameraState.zoom(-13));

			// Stop down: almost everything sharp.
			sp.getServer().runCommand("tp @p 0.5 -60 -1.5 0 16");
			ctx.runOnClient(mc -> {
				for (int i = 0; i < 30; i++) CameraState.stepAperture(1);
			});
			ctx.getInput().holdKey(o -> o.keyUse);
			ctx.waitTicks(40);
			ctx.takeScreenshot("framecraft-3-near-f22");
			ctx.getInput().releaseKey(o -> o.keyUse);

			// Shutter writes a framecraft_*.png into screenshots/.
			File shots = ctx.computeOnClient(mc -> new File(mc.gameDirectory, "screenshots"));
			int before = countPhotos(shots);
			ctx.runOnClient(mc -> CameraState.requestCapture());
			ctx.waitTicks(20);
			if (countPhotos(shots) <= before) {
				throw new AssertionError("Shutter did not save a photo in " + shots);
			}
			ctx.runOnClient(mc -> CameraState.toggle());
		}
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
		File[] files = dir.listFiles((d, name) -> name.startsWith("framecraft_"));
		return files == null ? 0 : files.length;
	}
}
