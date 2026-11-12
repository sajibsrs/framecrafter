package io.github.sajibsrs.framecrafter;

import io.github.sajibsrs.framecrafter.FramecrafterConfig.Bokeh;
import io.github.sajibsrs.framecrafter.FramecrafterConfig.PictureStyle;
import io.github.sajibsrs.framecrafter.FramecrafterConfig.WhiteBalance;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * The camera menu, opened with X in camera mode. Widgets sit in a column on the right and the
 * world is not blurred behind them, so every change shows in the live view straight away.
 */
public final class FramecrafterSettingsScreen extends Screen {
	private static final int COL_W = 124;
	private static final int ROW_H = 20;
	private static final int GAP = 4;
	private static final int PANEL = 0x99000000;

	private int panelX;
	private int panelY;
	private int panelW;
	private int panelH;
	private CycleButton<WhiteBalance> wbButton;
	private StepSlider kelvinSlider;

	public FramecrafterSettingsScreen() {
		super(Component.translatable("framecrafter.settings.title"));
	}

	@Override
	protected void init() {
		List<AbstractWidget> w = new ArrayList<>();

		// Exposure and colour
		double[] isos = new double[FramecrafterConfig.ISO_STOPS.length];
		for (int i = 0; i < isos.length; i++) {
			isos[i] = FramecrafterConfig.ISO_STOPS[i];
		}
		w.add(new StepSlider(isos, FramecrafterConfig.iso(),
			v -> "ISO: " + (int) v, v -> FramecrafterConfig.isoIndex = indexOf(FramecrafterConfig.ISO_STOPS, (int) v)));
		w.add(new StepSlider(steps(0, FramecrafterConfig.MAX_GRAIN, 1), FramecrafterConfig.grain,
			v -> v == 0 ? "Grain: Off" : "Grain: " + (int) v, v -> FramecrafterConfig.grain = (int) v));
		wbButton = CycleButton.<WhiteBalance>builder(wb -> Component.literal(wb.label), FramecrafterConfig.whiteBalance)
			.withValues(WhiteBalance.values())
			.create(Component.translatable("framecrafter.settings.whiteBalance"), (b, v) -> {
				FramecrafterConfig.whiteBalance = v;
				kelvinSlider.active = v == WhiteBalance.CUSTOM;
			});
		w.add(wbButton);
		kelvinSlider = new StepSlider(steps(FramecrafterConfig.MIN_KELVIN, FramecrafterConfig.MAX_KELVIN, 100), FramecrafterConfig.customKelvin,
			v -> "Kelvin: " + (int) v + "K", v -> FramecrafterConfig.customKelvin = (int) v);
		kelvinSlider.active = FramecrafterConfig.whiteBalance == WhiteBalance.CUSTOM;
		w.add(kelvinSlider);
		w.add(new StepSlider(steps(-FramecrafterConfig.MAX_TINT, FramecrafterConfig.MAX_TINT, 1), FramecrafterConfig.tint,
			v -> String.format(Locale.ROOT, "Tint: %+d %s", (int) v, v > 0 ? "M" : v < 0 ? "G" : ""), v -> FramecrafterConfig.tint = (int) v));
		w.add(CycleButton.<PictureStyle>builder(s -> Component.literal(s.label), FramecrafterConfig.pictureStyle)
			.withValues(PictureStyle.values())
			.create(Component.translatable("framecrafter.settings.pictureStyle"), (b, v) -> FramecrafterConfig.pictureStyle = v));

		// Lens
		w.add(toggle("vignetting", FramecrafterConfig.vignetting, v -> FramecrafterConfig.vignetting = v));
		w.add(toggle("distortion", FramecrafterConfig.distortion, v -> FramecrafterConfig.distortion = v));
		w.add(CycleButton.<Bokeh>builder(s -> Component.literal(s.label), FramecrafterConfig.bokeh)
			.withValues(Bokeh.values())
			.create(Component.translatable("framecrafter.settings.bokeh"), (b, v) -> FramecrafterConfig.bokeh = v));

		// Viewfinder
		w.add(toggle("showCameraInfo", FramecrafterConfig.showCameraInfo, v -> FramecrafterConfig.showCameraInfo = v));
		w.add(toggle("showGrid", FramecrafterConfig.showGrid, v -> FramecrafterConfig.showGrid = v));
		w.add(toggle("showHints", FramecrafterConfig.showHints, v -> FramecrafterConfig.showHints = v));
		w.add(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose()).build());

		int rows = (w.size() + 1) / 2;
		panelW = COL_W * 2 + GAP * 3;
		panelH = 14 + rows * (ROW_H + GAP) + GAP;
		panelX = this.width - panelW - 4;
		panelY = Math.max(4, (this.height - 16 - panelH) / 2);
		for (int i = 0; i < w.size(); i++) {
			AbstractWidget widget = w.get(i);
			widget.setWidth(COL_W);
			widget.setX(panelX + GAP + (i % 2) * (COL_W + GAP));
			widget.setY(panelY + 14 + (i / 2) * (ROW_H + GAP));
			addRenderableWidget(widget);
		}
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		// No blur or dimming: the photo behind is what is being adjusted.
		g.fill(panelX, panelY, panelX + panelW, panelY + panelH, PANEL);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		g.text(this.font, this.title, panelX + GAP, panelY + 4, 0xFFFFFFFF, false);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void removed() {
		FramecrafterConfig.save();
	}

	private static CycleButton<Boolean> toggle(String key, boolean value, Consumer<Boolean> setter) {
		return CycleButton.onOffBuilder(value)
			.create(Component.translatable("framecrafter.settings." + key), (b, v) -> setter.accept(v));
	}

	private static double[] steps(double min, double max, double step) {
		int n = (int) Math.round((max - min) / step) + 1;
		double[] out = new double[n];
		for (int i = 0; i < n; i++) {
			out[i] = min + i * step;
		}
		return out;
	}

	private static int indexOf(int[] values, int v) {
		for (int i = 0; i < values.length; i++) {
			if (values[i] == v) {
				return i;
			}
		}
		return 0;
	}

	/** A slider that snaps to a fixed list of values. */
	private static final class StepSlider extends AbstractSliderButton {
		private final double[] values;
		private final DoubleFunction<String> label;
		private final DoubleConsumer setter;

		StepSlider(double[] values, double initial, DoubleFunction<String> label, DoubleConsumer setter) {
			super(0, 0, COL_W, ROW_H, Component.empty(), position(values, initial));
			this.values = values;
			this.label = label;
			this.setter = setter;
			updateMessage();
		}

		private static double position(double[] values, double v) {
			int best = 0;
			for (int i = 1; i < values.length; i++) {
				if (Math.abs(values[i] - v) < Math.abs(values[best] - v)) {
					best = i;
				}
			}
			return values.length < 2 ? 0 : (double) best / (values.length - 1);
		}

		private double current() {
			return values[(int) Math.round(this.value * (values.length - 1))];
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.literal(label.apply(current())));
		}

		@Override
		protected void applyValue() {
			setter.accept(current());
		}
	}
}
