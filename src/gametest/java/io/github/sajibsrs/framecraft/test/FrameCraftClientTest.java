package io.github.sajibsrs.framecraft.test;

import io.github.sajibsrs.framecraft.CameraState;
import java.io.File;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Builds a small scene with subjects at 3, 8, 20 and 50 m, then screenshots it with the
 * camera off, focused near (f/1.4), focused far, and stopped down (f/22). Also checks that the
 * shutter writes a photo.
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

			// Look at the near gold pillar.
			ctx.runOnClient(mc -> {
				CameraState.toggle();
				CameraState.zoom(0);
				for (int i = 0; i < 30; i++) CameraState.stepAperture(-1);
			});
			sp.getServer().runCommand("tp @p 0.5 -60 -1.5 0 16");
			ctx.waitTicks(40);
			ctx.takeScreenshot("framecraft-1-near-f1.4");
			System.setProperty("framecraft.debugDepth", "true");
			ctx.waitTicks(2);
			ctx.takeScreenshot("framecraft-1b-depth-debug");
			System.setProperty("framecraft.debugDepth", "false");

			// Look over the gold pillar at the far wall.
			sp.getServer().runCommand("tp @p 0.5 -60 -1.5 2 0");
			ctx.waitTicks(40);
			ctx.takeScreenshot("framecraft-2-far-f1.4");

			// Portrait-style: 135 mm wide open on the gold pillar from 9 m back.
			sp.getServer().runCommand("tp @p 0.5 -60 -6.5 0 6");
			ctx.runOnClient(mc -> CameraState.zoom(13));
			ctx.waitTicks(40);
			ctx.takeScreenshot("framecraft-4-135mm-f1.4");
			ctx.runOnClient(mc -> CameraState.zoom(-13));

			// Stop down: almost everything sharp.
			sp.getServer().runCommand("tp @p 0.5 -60 -1.5 0 16");
			ctx.runOnClient(mc -> {
				for (int i = 0; i < 30; i++) CameraState.stepAperture(1);
			});
			ctx.waitTicks(40);
			ctx.takeScreenshot("framecraft-3-near-f22");

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

	private static int countPhotos(File dir) {
		File[] files = dir.listFiles((d, name) -> name.startsWith("framecraft_"));
		return files == null ? 0 : files.length;
	}
}
