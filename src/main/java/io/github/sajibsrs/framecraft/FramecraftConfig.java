package io.github.sajibsrs.framecraft;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

/** Camera settings, kept in config/framecraft.properties. */
public final class FramecraftConfig {
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("framecraft.properties");

	public static final int[] ISO_STOPS = {100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600};
	public static final float MIN_EV = -3f;
	public static final float MAX_EV = 3f;
	public static final int MIN_KELVIN = 2500;
	public static final int MAX_KELVIN = 10000;
	public static final int MAX_TINT = 10;

	/** White balance as set on the camera; the light in Minecraft is taken to be daylight (5500 K). */
	public enum WhiteBalance {
		DAYLIGHT("Daylight", 5500, 0),
		CLOUDY("Cloudy", 6500, 0),
		SHADE("Shade", 7500, 0),
		TUNGSTEN("Tungsten", 3200, 0),
		FLUORESCENT("Fluorescent", 4000, 3),
		CUSTOM("Custom", 5500, 0);

		public final String label;
		public final int kelvin;
		public final int tint;

		WhiteBalance(String label, int kelvin, int tint) {
			this.label = label;
			this.kelvin = kelvin;
			this.tint = tint;
		}
	}

	public enum PictureStyle {
		STANDARD("Standard", 1.0f, 1.0f),
		NEUTRAL("Neutral", 0.9f, 0.85f),
		VIVID("Vivid", 1.1f, 1.3f),
		MONOCHROME("Monochrome", 1.05f, 0.0f);

		public final String label;
		public final float contrast;
		public final float saturation;

		PictureStyle(String label, float contrast, float saturation) {
			this.label = label;
			this.contrast = contrast;
			this.saturation = saturation;
		}
	}

	// Exposure and colour
	public static float exposureEv = 0f;
	public static int isoIndex = 0;
	public static WhiteBalance whiteBalance = WhiteBalance.CUSTOM;
	public static int customKelvin = 5500;
	public static int tint = 0;
	public static PictureStyle pictureStyle = PictureStyle.STANDARD;
	// Lens
	public static boolean vignetting = true;
	public static boolean distortion = false;
	// Viewfinder
	public static boolean showCameraInfo = false;
	public static boolean showGrid = true;
	public static boolean showHints = true;

	private FramecraftConfig() {
	}

	public static int iso() {
		return ISO_STOPS[isoIndex];
	}

	/** White balance in effect: the preset's, or the custom slider's. */
	public static int kelvin() {
		return whiteBalance == WhiteBalance.CUSTOM ? customKelvin : whiteBalance.kelvin;
	}

	public static int effectiveTint() {
		return tint + whiteBalance.tint;
	}

	public static void load() {
		if (!Files.exists(FILE)) {
			return;
		}
		Properties p = new Properties();
		try (Reader r = Files.newBufferedReader(FILE)) {
			p.load(r);
		} catch (IOException e) {
			Framecraft.LOGGER.warn("Could not read {}", FILE, e);
			return;
		}
		exposureEv = Math.clamp(num(p, "exposureEv", exposureEv), MIN_EV, MAX_EV);
		isoIndex = Math.clamp((int) num(p, "isoIndex", isoIndex), 0, ISO_STOPS.length - 1);
		whiteBalance = enumValue(p, "whiteBalance", whiteBalance);
		customKelvin = Math.clamp((int) num(p, "customKelvin", customKelvin), MIN_KELVIN, MAX_KELVIN);
		tint = Math.clamp((int) num(p, "tint", tint), -MAX_TINT, MAX_TINT);
		pictureStyle = enumValue(p, "pictureStyle", pictureStyle);
		vignetting = bool(p, "vignetting", vignetting);
		distortion = bool(p, "distortion", distortion);
		showCameraInfo = bool(p, "showCameraInfo", showCameraInfo);
		showGrid = bool(p, "showGrid", showGrid);
		showHints = bool(p, "showHints", showHints);
	}

	public static void save() {
		Properties p = new Properties();
		p.setProperty("exposureEv", Float.toString(exposureEv));
		p.setProperty("isoIndex", Integer.toString(isoIndex));
		p.setProperty("whiteBalance", whiteBalance.name());
		p.setProperty("customKelvin", Integer.toString(customKelvin));
		p.setProperty("tint", Integer.toString(tint));
		p.setProperty("pictureStyle", pictureStyle.name());
		p.setProperty("vignetting", Boolean.toString(vignetting));
		p.setProperty("distortion", Boolean.toString(distortion));
		p.setProperty("showCameraInfo", Boolean.toString(showCameraInfo));
		p.setProperty("showGrid", Boolean.toString(showGrid));
		p.setProperty("showHints", Boolean.toString(showHints));
		try {
			Files.createDirectories(FILE.getParent());
			try (Writer w = Files.newBufferedWriter(FILE)) {
				p.store(w, "Framecraft settings");
			}
		} catch (IOException e) {
			Framecraft.LOGGER.warn("Could not write {}", FILE, e);
		}
	}

	private static boolean bool(Properties p, String key, boolean fallback) {
		String v = p.getProperty(key);
		return v == null ? fallback : Boolean.parseBoolean(v.trim());
	}

	private static float num(Properties p, String key, float fallback) {
		try {
			String v = p.getProperty(key);
			return v == null ? fallback : Float.parseFloat(v.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static <E extends Enum<E>> E enumValue(Properties p, String key, E fallback) {
		String v = p.getProperty(key);
		if (v == null) {
			return fallback;
		}
		try {
			return Enum.valueOf(fallback.getDeclaringClass(), v.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return fallback;
		}
	}
}
