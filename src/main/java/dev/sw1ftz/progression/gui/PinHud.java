package dev.sw1ftz.progression.gui;

import dev.sw1ftz.progression.Advisor;
import dev.sw1ftz.progression.CopperTracker;
import dev.sw1ftz.progression.GearItem;
import dev.sw1ftz.progression.Guide;
import dev.sw1ftz.progression.ItemDatabase;
import dev.sw1ftz.progression.PlayerProfile;
import dev.sw1ftz.progression.ProgressionClient;
import dev.sw1ftz.progression.Requirement;
import dev.sw1ftz.progression.SkillCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The floating "Working towards" list of everything you pinned in the guide. */
public final class PinHud {
	private static final int MAX_WIDTH = 200;
	private static final int MAX_MATERIALS = 4;

	private record Line(String text, int color, boolean indent) {
	}

	private static int width, height;

	private PinHud() {
	}

	public static int width() {
		return width;
	}

	public static int height() {
		return height;
	}

	public static void draw(GuiGraphicsExtractor graphics, boolean force) {
		Minecraft mc = Minecraft.getInstance();
		PlayerProfile profile = ProgressionClient.get().profile();
		boolean garden = CopperTracker.inGarden(mc);
		if (!force && (!profile.showHud || (profile.pins.isEmpty() && !garden) || mc.gui.hud.isHidden())) {
			return;
		}
		Font font = mc.font;
		List<Line> lines = new ArrayList<>();
		if (garden) {
			lines.add(new Line(profile.copper < 0 ? "Copper: sync your API key" : String.format(Locale.ROOT, "Copper: %,d", profile.copper), 0xFFFF9955, false));
		}
		if (force || !profile.pins.isEmpty()) {
			lines.add(new Line("Working Towards", Theme.ACCENT, false));
		}
		if (profile.pins.isEmpty() && force) {
			lines.add(new Line("Shift+click things in the guide to pin them", Theme.MUTED, false));
		}
		for (String pin : profile.pins) {
			addPin(lines, pin, profile);
		}

		int w = 0;
		for (Line line : lines) {
			w = Math.max(w, font.width(line.text()) + (line.indent() ? 8 : 0));
		}
		w = Math.min(w, MAX_WIDTH);
		int lineHeight = font.lineHeight + 2;
		width = w;
		height = lines.size() * lineHeight;

		int x = profile.hudX;
		int y = profile.hudY;
		int pad = Theme.HUD_PADDING;
		Theme.roundedBox(graphics, x - pad, y - pad, x + w + pad, y + height + 2, Theme.HUD_BACKGROUND);
		for (Line line : lines) {
			int lx = x + (line.indent() ? 8 : 0);
			graphics.text(font, trim(font, line.text(), w - (lx - x)), lx, y, line.color(), true);
			y += lineHeight;
		}
	}

	private static String trim(Font font, String text, int maxWidth) {
		if (font.width(text) <= maxWidth) {
			return text;
		}
		while (!text.isEmpty() && font.width(text + "...") > maxWidth) {
			text = text.substring(0, text.length() - 1);
		}
		return text + "...";
	}

	private static void addPin(List<Line> lines, String pin, PlayerProfile profile) {
		int colon = pin.indexOf(':');
		String kind = pin.substring(0, colon);
		String key = pin.substring(colon + 1);
		switch (kind) {
			case "SKILL" -> {
				SkillCategory skill;
				try {
					skill = SkillCategory.valueOf(key);
				} catch (IllegalArgumentException e) {
					return;
				}
				lines.add(new Line(skill.title + " " + skill.level(profile), 0xFF55FFFF, false));
				Guide.Entry next = ProgressionClient.get().guide().area(skill, profile).next();
				if (next == null) {
					lines.add(new Line("In the best area", Theme.GOOD, true));
				} else {
					lines.add(new Line("Next: " + next.name(), Theme.TEXT, true));
					for (String missing : next.missing(profile)) {
						lines.add(new Line("✖ " + missing, Theme.BAD, true));
					}
				}
			}
			case "SLAYER" -> {
				int level = profile.slayers.getOrDefault(key, 0);
				lines.add(new Line(InfoPages.slayerName(key) + " " + level, 0xFF55FFFF, false));
				Double xp = profile.slayerXp.get(key);
				if (xp != null) {
					long toNext = ProgressionClient.get().repo().slayerXpToNext(key, xp);
					lines.add(new Line(toNext < 0 ? "Maxed!" : String.format(Locale.ROOT, "%,d XP to level %d", toNext, level + 1),
							toNext < 0 ? Theme.GOOD : Theme.TEXT, true));
				}
			}
			case "ITEM" -> {
				ItemDatabase db = ProgressionClient.get().database();
				String rarity = "COMMON";
				List<Requirement> requirements = List.of();
				for (GearItem item : db.items()) {
					if (item.id().equals(key)) {
						rarity = item.rarity();
						requirements = item.requirements();
						break;
					}
				}
				if (requirements.isEmpty()) {
					for (ItemDatabase.Accessory a : db.accessories()) {
						if (a.id().equals(key)) {
							rarity = a.rarity();
							requirements = a.requirements();
							break;
						}
					}
				}
				boolean owned = profile.ownedItems.contains(key) || profile.accessories.contains(key);
				lines.add(new Line((owned ? "✔ " : "") + db.name(key), ProgressionScreen.rarityColor(rarity), false));
				if (owned) {
					lines.add(new Line("You have it! Unpin in the guide.", Theme.GOOD, true));
					return;
				}
				boolean all = true;
				for (Requirement r : requirements) {
					if (!profile.meets(r)) {
						lines.add(new Line("✖ " + r.text(), Theme.BAD, true));
						all = false;
					}
				}
				if (all && !requirements.isEmpty()) {
					lines.add(new Line("✔ Requirements met", Theme.GOOD, true));
				}
				Double price = Advisor.buyPrice(key);
				if (price != null) {
					String cost = "Cost " + ProgressionScreen.coins(price);
					if (profile.purse > 0) {
						cost += profile.purse >= price ? "  (you can afford it)" : "  (" + ProgressionScreen.coins(price - profile.purse) + " short)";
					}
					lines.add(new Line(cost, Theme.COINS, true));
				}
				Advisor.Craft craft = Advisor.craft(profile, List.of(key));
				if (craft != null && !craft.loading()) {
					lines.add(new Line("Craft " + (craft.cost() == null ? "(no price)" : ProgressionScreen.coins(craft.cost())), Theme.GOOD, true));
					for (String t : craft.tradeIn()) {
						lines.add(new Line("✔ " + t, 0xFF55FFFF, true));
					}
					// Keep long recipes from filling the screen; the full list is in the guide.
					for (String m : craft.materials().subList(0, Math.min(MAX_MATERIALS, craft.materials().size()))) {
						lines.add(new Line("• " + m, Theme.MUTED, true));
					}
					if (craft.materials().size() > MAX_MATERIALS) {
						lines.add(new Line("+" + (craft.materials().size() - MAX_MATERIALS) + " more", Theme.MUTED, true));
					}
				}
			}
			default -> {
			}
		}
	}
}
