package dev.sw1ftz.progression.gui;

import dev.sw1ftz.progression.PlayerProfile;
import dev.sw1ftz.progression.ProgressionClient;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Paste your SkyCrypt (or Hypixel) API key and sync your profile from it. */
public class ApiKeyScreen extends Screen {
	private static final String KEY_PAGE = "https://sky.shiiyu.moe/dashboard/developer";

	private final Screen parent;
	private final PlayerProfile profile = ProgressionClient.get().profile();
	private int left, top, panelWidth, panelHeight;
	private EditBox keyBox;

	public ApiKeyScreen(Screen parent) {
		super(Component.literal("API Key"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		panelWidth = Math.min(320, width - 16);
		panelHeight = Math.min(170, height - 16);
		left = (width - panelWidth) / 2;
		top = (height - panelHeight) / 2;

		keyBox = new EditBox(font, left + 12, top + Theme.HEADER_HEIGHT + 52, panelWidth - 24, 18, Component.literal("API key"));
		keyBox.setMaxLength(256);
		keyBox.setValue(profile.apiKey);
		keyBox.setHint(Component.literal("Paste your key here (Ctrl+V)").withStyle(ChatFormatting.DARK_GRAY));
		addRenderableWidget(keyBox);
		setInitialFocus(keyBox);

		int buttonY = top + panelHeight - 28;
		int buttonWidth = (panelWidth - 24 - 12) / 3;
		addRenderableWidget(Button.builder(Component.literal("Get SkyCrypt key"), ConfirmLinkScreen.confirmLink(this, KEY_PAGE))
				.bounds(left + 12, buttonY, buttonWidth, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Save & Sync").withStyle(ChatFormatting.GREEN), b -> {
					profile.apiKey = keyBox.getValue().trim();
					profile.lastApiSync = 0;
					profile.save();
					ProgressionClient.get().api().sync(profile);
				})
				.bounds(left + 12 + buttonWidth + 6, buttonY, buttonWidth, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
				.bounds(left + 12 + (buttonWidth + 6) * 2, buttonY, buttonWidth, 20).build());
	}

	@Override
	public void onClose() {
		// Keep whatever was typed even without pressing Save.
		String typed = keyBox.getValue().trim();
		if (!typed.equals(profile.apiKey)) {
			profile.apiKey = typed;
			profile.save();
		}
		minecraft.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(graphics, mouseX, mouseY, partialTick);
		Theme.panel(graphics, left, top, panelWidth, panelHeight);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.text(font, "SkyCrypt or Hypixel API Key", left + 10, top + 8, Theme.ACCENT, true);
		graphics.textWithWordWrap(font, Component.literal("With a SkyCrypt key (or a Hypixel one) the guide reads your slayers, dungeons, Kuudra, "
						+ "collections, accessories and gear by itself. It's saved only on your computer."),
				left + 12, top + Theme.HEADER_HEIGHT + 10, panelWidth - 24, Theme.MUTED, false);
		String status = ProgressionClient.get().api().status();
		if (status.isEmpty()) {
			status = profile.apiKey.isBlank() ? "No key saved yet" : "Key saved";
		}
		boolean bad = status.contains("Invalid") || status.contains("failed") || status.contains("error");
		graphics.text(font, status, left + 12, top + Theme.HEADER_HEIGHT + 78, bad ? Theme.BAD : profile.apiKey.isBlank() ? Theme.MUTED : Theme.GOOD, false);
	}
}
