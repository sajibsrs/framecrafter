package io.github.sajibsrs.framecraft;

import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** The overlay drawn instead of the vanilla HUD while in camera mode. */
public final class Viewfinder {
	private static final int LINE = 0x66FFFFFF;
	private static final int TEXT = 0xFFFFFFFF;
	private static final int FOCUS = 0xFFFFFFFF;
	private static final int FOCUS_LOCKED = 0xFF7CFF7C;
	private static final int FLASH = 0xFFFFFFFF;

	private Viewfinder() {
	}

	public static void draw(Minecraft mc, GuiGraphicsExtractor g) {
		int w = g.guiWidth();
		int h = g.guiHeight();

		if (CameraState.isFlashing()) {
			g.fill(0, 0, w, h, FLASH);
			return;
		}

		// Rule-of-thirds grid.
		for (int i = 1; i <= 2; i++) {
			int x = w * i / 3;
			int y = h * i / 3;
			g.fill(x, 0, x + 1, h, LINE);
			g.fill(0, y, w, y + 1, LINE);
		}

		// Focus point.
		int cx = w / 2;
		int cy = h / 2;
		int s = 6;
		int color = CameraState.isFocusLocked() ? FOCUS_LOCKED : FOCUS;
		g.outline(cx - s, cy - s, s * 2 + 1, s * 2 + 1, color);

		// Settings readout along the bottom, like an LCD strip.
		Font font = mc.font;
		String readout = String.format(Locale.ROOT, "%dmm   f/%s   %s",
			Math.round(CameraState.focalMm()),
			formatStop(CameraState.fNumber()),
			formatDistance(CameraState.focusM()));
		g.fill(0, h - 16, w, h, 0x88000000);
		g.centeredText(font, readout, cx, h - 12, TEXT);
		g.text(font, "Scroll: zoom   Ctrl+Scroll: aperture   Right: hold focus   Left: shoot", 4, 4, 0x99FFFFFF, true);
	}

	private static String formatStop(float n) {
		return n == Math.floor(n) ? Integer.toString((int) n) : String.format(Locale.ROOT, "%.1f", n);
	}

	private static String formatDistance(double m) {
		if (m >= 500.0) {
			return "∞";
		}
		return m < 10.0 ? String.format(Locale.ROOT, "%.1fm", m) : String.format(Locale.ROOT, "%dm", Math.round(m));
	}
}
