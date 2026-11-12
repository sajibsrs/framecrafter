package io.github.sajibsrs.framecrafter;

import java.util.ArrayList;
import java.util.List;
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
	private static final int PANEL = 0x88000000;

	private Viewfinder() {
	}

	public static void draw(Minecraft mc, GuiGraphicsExtractor g) {
		int w = g.guiWidth();
		int h = g.guiHeight();

		if (CameraState.isFlashing()) {
			g.fill(0, 0, w, h, FLASH);
			return;
		}

		if (FramecrafterConfig.showGrid) {
			// Rule-of-thirds grid.
			for (int i = 1; i <= 2; i++) {
				int x = w * i / 3;
				int y = h * i / 3;
				g.fill(x, 0, x + 1, h, LINE);
				g.fill(0, y, w, y + 1, LINE);
			}
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
		if (FramecrafterConfig.showCameraInfo) {
			drawInfo(mc, g, font, h - 16);
		}
		// Control hints, not while the camera menu is open; split over two lines when narrow.
		if (!FramecrafterConfig.showHints || mc.gui.screen() != null) {
			return;
		}
		String lens = "Scroll: zoom   Ctrl+Scroll: aperture   X: menu";
		String body = "Right: focus   Ctrl+Mouse: focus point   Left: shoot";
		String both = lens + "   " + body;
		if (font.width(both) <= w - 8) {
			g.text(font, both, 4, 4, HINT, true);
		} else {
			g.text(font, lens, 4, 4, HINT, true);
			g.text(font, body, 4, 14, HINT, true);
		}
	}

	/** What goes into a photo: only the info panel, and only when Camera Info is on. */
	public static void drawPhotoInfo(Minecraft mc, GuiGraphicsExtractor g) {
		if (FramecrafterConfig.showCameraInfo) {
			drawInfo(mc, g, mc.font, g.guiHeight());
		}
	}

	/** Everything the info panel shows. */
	private static List<String> infoLines(Minecraft mc) {
		int frameW = mc.gameRenderer.mainRenderTarget().width;
		int frameH = mc.gameRenderer.mainRenderTarget().height;
		double sharp = CameraState.SHARP_RADIUS_PX;
		double s = CameraState.focusM();

		double vFov = CameraState.verticalFovDeg();
		double hFov = Math.toDegrees(2.0 * Math.atan(Math.tan(Math.toRadians(vFov) / 2.0) * frameW / Math.max(1, frameH)));
		double near = CameraState.dofNearM(s, sharp, frameH);
		double far = CameraState.dofFarM(s, sharp, frameH);
		double subject = CameraState.subjectM();
		float focal = CameraState.focalMm();
		float n = CameraState.fNumber();
		float k = CameraState.distortionK();

		List<String> lines = new ArrayList<>();
		lines.add(String.format(Locale.ROOT, "Lens %.1fmm f/%s (pupil %.1fmm)   focus %s   AF %s", focal, formatStop(n), focal / n,
			formatDistance(s), CameraState.afState().name().toLowerCase(Locale.ROOT)));
		lines.add(String.format(Locale.ROOT, "FOV %.1f° x %.1f°   min focus %s", vFov, hFov, formatDistance(CameraState.minFocusM())));
		lines.add("DOF " + formatDistance(near) + " - " + formatDistance(far)
			+ (Double.isInfinite(far) ? "" : " (" + formatDistance(far - near) + ")")
			+ "   hyperfocal " + formatDistance(CameraState.hyperfocalM(sharp, frameH)));
		lines.add(subject > 0
			? String.format(Locale.ROOT, "Subject %s (%.1f blocks)   blur %.1fpx", formatDistance(subject),
				subject / CameraState.sceneScale(), CameraState.blurRadiusPx(subject, s, frameH))
			: "Subject none");
		lines.add(String.format(Locale.ROOT, "Bg blur %.1fpx   CoC %.0fµm   %.0f samples   %dx%d",
			CameraState.blurRadiusPx(1.0e5, s, frameH), CameraState.cocDiameterM(sharp, frameH) * 1.0e6,
			DofEffect.sampleCount(frameH, CameraState.isCapturing()), frameW, frameH));
		FramecrafterConfig.WhiteBalance wb = FramecrafterConfig.whiteBalance;
		lines.add(String.format(Locale.ROOT, "ISO %d (%+.1fEV)   grain %d   WB %s %dK tint %+d   %s",
			FramecrafterConfig.iso(), FramecrafterConfig.exposureEv(), FramecrafterConfig.grain, wb.label, FramecrafterConfig.kelvin(),
			FramecrafterConfig.effectiveTint(), FramecrafterConfig.pictureStyle.label));
		lines.add(String.format(Locale.ROOT, "Vignette %.1f stops at corner   %s", CameraState.vignetteEv(),
			k == 0f ? "rectilinear, no distortion"
				: String.format(Locale.ROOT, "distortion %s k=%+.1f%%", k > 0 ? "barrel" : "pincushion", k * 100f)));
		return lines;
	}

	/**
	 * Compact info panel in the bottom-left corner, its bottom edge at {@code bottom}, drawn at
	 * a smaller text size. The scale keeps each font pixel a whole number of window pixels, so
	 * the text stays crisp.
	 */
	private static void drawInfo(Minecraft mc, GuiGraphicsExtractor g, Font font, int bottom) {
		List<String> lines = infoLines(mc);
		int guiScale = Math.max(1, mc.getWindow().getGuiScale());
		float scale = (float) Math.ceil(guiScale / 2.0) / guiScale;
		int pad = 3;
		int lineH = 9;
		int textW = 0;
		for (String line : lines) {
			textW = Math.max(textW, font.width(line));
		}
		int panelW = textW + pad * 2;
		int panelH = lines.size() * lineH + pad * 2 - 1;

		g.pose().pushMatrix();
		g.pose().translate(2f, bottom - 2 - panelH * scale);
		g.pose().scale(scale, scale);
		g.fill(0, 0, panelW, panelH, PANEL);
		for (int i = 0; i < lines.size(); i++) {
			g.text(font, lines.get(i), pad, pad + i * lineH, TEXT, false);
		}
		g.pose().popMatrix();
	}

	private static String formatStop(float n) {
		return n == Math.floor(n) ? Integer.toString((int) n) : String.format(Locale.ROOT, "%.1f", n);
	}

	private static String formatDistance(double m) {
		if (m >= 500.0 || Double.isInfinite(m)) {
			return "∞";
		}
		return m < 10.0 ? String.format(Locale.ROOT, "%.1fm", m) : String.format(Locale.ROOT, "%dm", Math.round(m));
	}
}
