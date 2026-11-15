package dev.sw1ftz.progression.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Shared colors and panel style, the same as the Bazaar Flipper mod:
 * Odin's dark gray panels and blue accent, and SkyHanni-style Minecraft color-code text.
 */
public final class Theme {
	// Odin: "Background Color" #262626 and Click GUI "Color" #3296DC
	public static final int PANEL = 0xF0262626;
	public static final int HEADER = 0xFF1C1C1C;
	public static final int PANEL_INSET = 0x80000000;
	public static final int BORDER = 0xFF3A3A3A;
	public static final int HIGHLIGHT = 0xFF3296DC;
	public static final int ROW_HOVER = 0x403296DC;

	// Minecraft color codes, as SkyHanni and Skyblocker use them
	public static final int ACCENT = 0xFFFFAA00; // §6 gold headings
	public static final int TEXT = 0xFFFFFFFF; // §f
	public static final int MUTED = 0xFFAAAAAA; // §7
	public static final int GOOD = 0xFF55FF55; // §a
	public static final int COINS = 0xFFFFAA00; // §6
	public static final int BAD = 0xFFFF5555; // §c

	/** SkyHanni's HUD background: black at 80/255 opacity with a 5px border. */
	public static final int HUD_BACKGROUND = 0x50000000;
	public static final int HUD_PADDING = 5;

	public static final int HEADER_HEIGHT = 24;

	private Theme() {
	}

	/** A dark gray panel with a darker header and a blue accent line under it. */
	public static void panel(GuiGraphicsExtractor graphics, int left, int top, int width, int height) {
		int right = left + width;
		int bottom = top + height;
		graphics.fill(left + 3, top + 3, right + 3, bottom + 3, 0x60000000);
		graphics.fill(left, top, right, bottom, PANEL);
		graphics.fill(left, top, right, top + HEADER_HEIGHT, HEADER);
		graphics.fill(left, top + HEADER_HEIGHT, right, top + HEADER_HEIGHT + 1, HIGHLIGHT);
		graphics.outline(left - 1, top - 1, width + 2, height + 2, BORDER);
	}

	/** A recessed box for grouping content inside a panel. */
	public static void inset(GuiGraphicsExtractor graphics, int left, int top, int width, int height) {
		graphics.fill(left, top, left + width, top + height, PANEL_INSET);
		graphics.outline(left, top, width, height, BORDER);
	}

	/** A translucent box with rounded corners; rows never overlap so the alpha stays even. */
	public static void roundedBox(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int color) {
		graphics.fill(x0 + 2, y0, x1 - 2, y0 + 1, color);
		graphics.fill(x0 + 1, y0 + 1, x1 - 1, y0 + 2, color);
		graphics.fill(x0, y0 + 2, x1, y1 - 2, color);
		graphics.fill(x0 + 1, y1 - 2, x1 - 1, y1 - 1, color);
		graphics.fill(x0 + 2, y1 - 1, x1 - 2, y1, color);
	}
}
