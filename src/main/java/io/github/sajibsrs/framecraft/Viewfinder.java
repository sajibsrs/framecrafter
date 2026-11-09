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
	private static final int FOCUS_SHARP = 0xFF7CFF7C;
	private static final int FOCUS_FAILED = 0xFFFF5A5A;
	private static final int FLASH = 0xFFFFFFFF;
	private static final int HINT = 0x99FFFFFF;

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

		// Focus point: green once the subject under it is sharp, red if the lens can't get there.
		int cx = w / 2;
		int cy = h / 2;
		int fx = Math.round(cx + CameraState.focusPointX() * w / 2f);
		int fy = Math.round(cy - CameraState.focusPointY() * h / 2f);
		int s = 6;
		int color = switch (CameraState.afState()) {
			case SHARP -> FOCUS_SHARP;
			case FAILED -> FOCUS_FAILED;
			case IDLE, HUNTING -> FOCUS;
		};
		g.outline(fx - s, fy - s, s * 2 + 1, s * 2 + 1, color);

		// Settings readout along the bottom, like an LCD strip.
		Font font = mc.font;
		String readout = String.format(Locale.ROOT, "%dmm   f/%s   %s",
			Math.round(CameraState.focalMm()),
			formatStop(CameraState.fNumber()),
			formatDistance(CameraState.focusM()));
		g.fill(0, h - 16, w, h, 0x88000000);
		g.centeredText(font, readout, cx, h - 12, TEXT);
		// Control hints; split over two lines when the window is too narrow for one.
		String lens = "Scroll: zoom   Ctrl+Scroll: aperture";
		String body = "Right: focus   Ctrl+Mouse: AF point   Left: shoot";
		String both = lens + "   " + body;
		if (font.width(both) <= w - 8) {
			g.text(font, both, 4, 4, HINT, true);
		} else {
			g.text(font, lens, 4, 4, HINT, true);
			g.text(font, body, 4, 14, HINT, true);
		}
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
