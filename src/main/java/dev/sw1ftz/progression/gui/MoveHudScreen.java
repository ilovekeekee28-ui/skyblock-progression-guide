package dev.sw1ftz.progression.gui;

import dev.sw1ftz.progression.PlayerProfile;
import dev.sw1ftz.progression.ProgressionClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/** Drag the pinned-goals HUD around, show or hide it, and clear the pins. */
public class MoveHudScreen extends Screen {
	private final Screen parent;
	private final PlayerProfile profile = ProgressionClient.get().profile();
	private boolean dragging;
	private double grabX, grabY;

	public MoveHudScreen(Screen parent) {
		super(Component.literal("Move HUD"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int y = height - 30;
		addRenderableWidget(Button.builder(Component.literal(profile.showHud ? "Hide HUD" : "Show HUD"), b -> {
			profile.showHud = !profile.showHud;
			b.setMessage(Component.literal(profile.showHud ? "Hide HUD" : "Show HUD"));
		}).bounds(width / 2 - 158, y, 76, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Clear Pins"), b -> profile.pins.clear())
				.bounds(width / 2 - 78, y, 76, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Reset"), b -> {
			profile.hudX = 6;
			profile.hudY = 60;
		}).bounds(width / 2 + 2, y, 76, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
				.bounds(width / 2 + 82, y, 76, 20).build());
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		// No blur, so the game stays visible behind the HUD.
		graphics.fill(0, 0, width, height, 0x30000000);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		PinHud.draw(graphics, true);
		int pad = Theme.HUD_PADDING;
		boolean hovered = isOverHud(mouseX, mouseY);
		graphics.outline(profile.hudX - pad - 1, profile.hudY - pad - 1, PinHud.width() + pad * 2 + 2, PinHud.height() + pad + 4,
				dragging || hovered ? Theme.HIGHLIGHT : 0xA0FFFFFF);
		graphics.centeredText(font, "Drag the list to move it", width / 2, height - 56, Theme.ACCENT);
		graphics.centeredText(font, profile.showHud ? "Press Done or Esc when finished" : "The HUD is hidden in game", width / 2, height - 44, Theme.MUTED);
	}

	private boolean isOverHud(double mouseX, double mouseY) {
		int pad = Theme.HUD_PADDING;
		return mouseX >= profile.hudX - pad && mouseX < profile.hudX + PinHud.width() + pad
				&& mouseY >= profile.hudY - pad && mouseY < profile.hudY + PinHud.height() + 2;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (event.button() == 0 && isOverHud(event.x(), event.y())) {
			dragging = true;
			grabX = event.x() - profile.hudX;
			grabY = event.y() - profile.hudY;
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (dragging) {
			int pad = Theme.HUD_PADDING;
			profile.hudX = Mth.clamp((int) (event.x() - grabX), pad, Math.max(pad, width - PinHud.width() - pad));
			profile.hudY = Mth.clamp((int) (event.y() - grabY), pad, Math.max(pad, height - PinHud.height() - 2));
			return true;
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		dragging = false;
		return super.mouseReleased(event);
	}

	@Override
	public void onClose() {
		profile.save();
		minecraft.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
