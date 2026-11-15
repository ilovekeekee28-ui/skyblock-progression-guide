package dev.sw1ftz.progression.gui;

import dev.sw1ftz.progression.PlayerProfile;
import dev.sw1ftz.progression.ProgressionClient;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/** Lets you type in your levels. Skills also fill in automatically from SkyBlock's /skills menu. */
public class LevelsScreen extends Screen {
	private final Screen parent;
	private final PlayerProfile profile = ProgressionClient.get().profile();
	private final List<Field> fields = new ArrayList<>();
	private int left, top, panelWidth, panelHeight;
	private Button saveButton;
	private EditBox apiKeyBox;
	private int apiRowY;

	public LevelsScreen(Screen parent) {
		super(Component.literal("Your Levels"));
		this.parent = parent;
		for (String skill : new String[] {"FARMING", "MINING", "COMBAT", "FORAGING", "FISHING", "HUNTING"}) {
			String label = skill.charAt(0) + skill.substring(1).toLowerCase();
			fields.add(new Field(label, 60, () -> profile.skill(skill), v -> profile.skills.put(skill, v)));
		}
		fields.add(new Field("Catacombs", 50, () -> profile.catacombsLevel, v -> profile.catacombsLevel = v));
		fields.add(new Field("Floor done", 7, () -> profile.catacombsFloor, v -> profile.catacombsFloor = v,
				"Highest Catacombs floor you've completed (0-7)."));
		fields.add(new Field("HOTM tier", 10, () -> profile.hotmTier, v -> profile.hotmTier = v,
				"Your Heart of the Mountain tier (shown in /hotm)."));
		fields.add(new Field("Garden level", 15, () -> profile.gardenLevel, v -> profile.gardenLevel = v,
				"Your Garden level (shown in the Garden's Desk menu)."));
	}

	@Override
	protected void init() {
		panelWidth = Math.min(340, width - 16);
		panelHeight = Math.min(250, height - 16);
		left = (width - panelWidth) / 2;
		top = (height - panelHeight) / 2;

		int columnWidth = (panelWidth - 30) / 2;
		int rows = (fields.size() + 1) / 2;
		for (int i = 0; i < fields.size(); i++) {
			Field field = fields.get(i);
			int column = i / rows;
			int row = i % rows;
			int x = left + 12 + column * (columnWidth + 6);
			int y = top + Theme.HEADER_HEIGHT + 14 + row * 24;
			EditBox box = new EditBox(font, x + columnWidth - 44, y, 40, 16, Component.literal(field.label));
			box.setMaxLength(3);
			box.setValue(field.text);
			box.setResponder(text -> {
				field.text = text;
				validate();
			});
			if (field.tooltip != null) {
				box.setTooltip(Tooltip.create(Component.literal(field.tooltip)));
			}
			field.box = box;
			field.labelX = x;
			field.labelY = y + 4;
			addRenderableWidget(box);
		}

		// Right under the level fields, so it never overlaps them on small windows.
		int keyY = top + Theme.HEADER_HEIGHT + 14 + rows * 24 + 2;
		apiRowY = keyY;
		apiKeyBox = new EditBox(font, left + 70, keyY, panelWidth - 70 - 12 - 66, 16, Component.literal("API key"));
		apiKeyBox.setMaxLength(256);
		apiKeyBox.setValue(profile.apiKey);
		apiKeyBox.setHint(Component.literal("optional: SkyCrypt or Hypixel API key").withStyle(ChatFormatting.DARK_GRAY));
		apiKeyBox.setTooltip(Tooltip.create(Component.literal("Your own SkyCrypt or Hypixel API key. With it the guide reads your slayers, dungeons, "
				+ "Kuudra, trophy fish, collections, accessories and gear automatically.\nSaved only on your computer.")));
		addRenderableWidget(apiKeyBox);
		addRenderableWidget(Button.builder(Component.literal("Sync"), b -> {
					profile.apiKey = apiKeyBox.getValue().trim();
					profile.lastApiSync = 0;
					profile.save();
					ProgressionClient.get().api().sync(profile);
				})
				.bounds(left + panelWidth - 12 - 60, keyY - 2, 60, 20)
				.tooltip(Tooltip.create(Component.literal("Save the key and read your profile from Hypixel now.")))
				.build());

		int buttonY = top + panelHeight - 28;
		addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
				.bounds(left + panelWidth / 2 - 104, buttonY, 100, 20).build());
		saveButton = addRenderableWidget(Button.builder(Component.literal("Save").withStyle(ChatFormatting.GREEN), b -> save())
				.bounds(left + panelWidth / 2 + 4, buttonY, 100, 20).build());
		validate();
	}

	private void validate() {
		boolean ok = true;
		for (Field field : fields) {
			boolean valid = field.parse() != null;
			field.box.setTextColor(valid ? Theme.TEXT : Theme.BAD);
			ok &= valid;
		}
		saveButton.active = ok;
	}

	private void save() {
		for (Field field : fields) {
			Integer value = field.parse();
			if (value == null) {
				return;
			}
			field.setter.accept(value);
		}
		profile.apiKey = apiKeyBox.getValue().trim();
		ProgressionClient.get().profileChanged();
		onClose();
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
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
		graphics.text(font, "Your Levels", left + 10, top + 8, Theme.ACCENT, true);
		for (Field field : fields) {
			graphics.text(font, field.label, field.labelX, field.labelY, field.parse() != null ? Theme.TEXT : Theme.BAD, true);
		}
		graphics.text(font, "API key", left + 12, apiRowY + 4, Theme.TEXT, true);
		String status = ProgressionClient.get().api().status();
		graphics.text(font, status.isEmpty() ? "No key? Opening each menu once works too." : status,
				left + 12, apiRowY + 22, Theme.MUTED, false);
	}

	private static final class Field {
		final String label;
		final int max;
		final IntConsumer setter;
		final String tooltip;
		String text;
		EditBox box;
		int labelX, labelY;

		Field(String label, int max, IntSupplier getter, IntConsumer setter) {
			this(label, max, getter, setter, null);
		}

		Field(String label, int max, IntSupplier getter, IntConsumer setter, String tooltip) {
			this.label = label;
			this.max = max;
			this.setter = setter;
			this.tooltip = tooltip;
			this.text = String.valueOf(getter.getAsInt());
		}

		Integer parse() {
			try {
				int value = Integer.parseInt(text.trim());
				return value >= 0 && value <= max ? value : null;
			} catch (NumberFormatException e) {
				return null;
			}
		}
	}
}
