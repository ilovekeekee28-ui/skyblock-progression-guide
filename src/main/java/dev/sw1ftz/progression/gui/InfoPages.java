package dev.sw1ftz.progression.gui;

import dev.sw1ftz.progression.Advisor;
import dev.sw1ftz.progression.GearItem;
import dev.sw1ftz.progression.ItemDatabase;
import dev.sw1ftz.progression.MinionTracker;
import dev.sw1ftz.progression.PlayerProfile;
import dev.sw1ftz.progression.ProgressionClient;
import dev.sw1ftz.progression.RepoData;
import dev.sw1ftz.progression.Requirement;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds the rows for the Progress, Accessories, Upgrades and Minions tabs. */
final class InfoPages {
	private InfoPages() {
	}

	/**
	 * One list row; a section row is a small heading. Tooltip and click (right-click) may be null.
	 * {@code recipeId} is the SkyBlock item a left-click finds on the auction house (ctrl+click: its recipe), or null.
	 */
	record Row(String title, int titleColor, String right, int rightColor, String sub, int subColor,
			   List<Component> tooltip, Runnable click, boolean section, String recipeId, String pin) {
		Row(String title, int titleColor, String right, int rightColor, String sub, int subColor,
			List<Component> tooltip, Runnable click, boolean section) {
			this(title, titleColor, right, rightColor, sub, subColor, tooltip, click, section, null, null);
		}

		Row withRecipe(String id) {
			List<Component> tip = new ArrayList<>(tooltip == null ? List.of(Component.literal(title).withStyle(ChatFormatting.GOLD)) : tooltip);
			tip.add(Component.literal("Click to find it on the auction house").withStyle(ChatFormatting.YELLOW));
			tip.add(Component.literal("Ctrl+click to view its recipe").withStyle(ChatFormatting.YELLOW));
			return new Row(title, titleColor, right, rightColor, sub, subColor, tip, click, section, id, null).withPin("ITEM:" + id);
		}

		/** Shift+click pins it to the floating HUD. */
		Row withPin(String key) {
			List<Component> tip = new ArrayList<>(tooltip == null ? List.of(Component.literal(title).withStyle(ChatFormatting.GOLD)) : tooltip);
			boolean pinned = ProgressionClient.get().profile().pins.contains(key);
			tip.add(Component.literal(pinned ? "Shift+click to unpin from your HUD" : "Shift+click to pin to your HUD").withStyle(ChatFormatting.AQUA));
			return new Row(pinned ? "★ " + title : title, titleColor, right, rightColor, sub, subColor, tip, click, section, recipeId, key);
		}
		static Row section(String text) {
			return new Row(text, Theme.HIGHLIGHT, "", 0, "", 0, null, null, true);
		}

		static Row hint(String text) {
			return new Row(text, 0xFF777777, "", 0, "", 0, null, null, true);
		}
	}

	private static final Map<String, String> SLAYERS = new LinkedHashMap<>();

	static {
		SLAYERS.put("zombie", "Revenant Horror");
		SLAYERS.put("spider", "Tarantula Broodfather");
		SLAYERS.put("wolf", "Sven Packmaster");
		SLAYERS.put("enderman", "Voidgloom Seraph");
		SLAYERS.put("blaze", "Inferno Demonlord");
		SLAYERS.put("vampire", "Riftstalker Bloodfiend");
	}

	static String slayerName(String type) {
		return SLAYERS.getOrDefault(type, Requirement.pretty(type));
	}

	// ---------------------------------------------------------------- progress

	static List<Row> progress(PlayerProfile profile) {
		ProgressionClient client = ProgressionClient.get();
		RepoData repo = client.repo();
		List<Row> rows = new ArrayList<>();

		rows.add(Row.section("SLAYERS"));
		if (!profile.known.contains("SLAYER")) {
			rows.add(Row.hint("Open the Slayer menu once, or add an API key."));
		}
		for (Map.Entry<String, String> boss : SLAYERS.entrySet()) {
			String type = boss.getKey();
			int level = profile.slayers.getOrDefault(type, 0);
			Double xp = profile.slayerXp.get(type);
			String sub;
			if (xp != null) {
				long toNext = repo.slayerXpToNext(type, xp);
				sub = toNext < 0 ? "Maxed!" : String.format("%,d XP to level %d", toNext, level + 1);
			} else {
				sub = "Exact XP needs an API key";
			}
			List<String> unlocks = unlockedBy(r -> r.kind() == Requirement.Kind.SLAYER && r.key().equals(type) && r.level() == level + 1);
			if (!unlocks.isEmpty()) {
				sub += "  •  unlocks " + summary(unlocks);
			}
			rows.add(new Row(boss.getValue(), Theme.TEXT, "LVL " + level, level > 0 ? Theme.GOOD : Theme.MUTED,
					sub, Theme.MUTED, unlockTooltip(boss.getValue() + " " + (level + 1), unlocks), null, false).withPin("SLAYER:" + type));
		}

		rows.add(Row.section("DUNGEONS"));
		int floor = profile.catacombsFloor;
		List<String> floorUnlocks = unlockedBy(r -> r.kind() == Requirement.Kind.CATACOMBS_FLOOR && r.level() == floor + 1);
		List<String> levelUnlocks = unlockedBy(r -> r.level() == profile.catacombsLevel + 1 && (r.kind() == Requirement.Kind.CATACOMBS_LEVEL
				|| (r.kind() == Requirement.Kind.SKILL && r.key().equals("CATACOMBS"))));
		rows.add(new Row("Catacombs level", Theme.TEXT, String.valueOf(profile.catacombsLevel), Theme.GOOD,
				levelUnlocks.isEmpty() ? "Level up by running floors" : "Level " + (profile.catacombsLevel + 1) + " unlocks " + summary(levelUnlocks),
				Theme.MUTED, unlockTooltip("Catacombs " + (profile.catacombsLevel + 1), levelUnlocks), null, false));
		rows.add(new Row("Highest floor done", Theme.TEXT, floor == 0 ? "None" : "F" + floor, Theme.GOOD,
				floor >= 7 ? "All floors done, go for Master Mode" : "Next: Floor " + (floor + 1)
						+ (floorUnlocks.isEmpty() ? "" : "  •  unlocks " + summary(floorUnlocks)),
				Theme.MUTED, unlockTooltip("Floor " + (floor + 1), floorUnlocks), null, false));

		rows.add(Row.section("CRIMSON ISLE"));
		if (!profile.known.contains("KUUDRA")) {
			rows.add(Row.hint("Needs an API key (Edit My Levels)."));
		}
		int kuudra = profile.kuudraTiers.stream().mapToInt(Requirement.KUUDRA_TIERS::indexOf).max().orElse(-1);
		String nextKuudra = kuudra + 1 < Requirement.KUUDRA_TIERS.size() ? kuudraName(kuudra + 1) : null;
		final int nextTier = kuudra + 1;
		List<String> kuudraUnlocks = unlockedBy(r -> r.kind() == Requirement.Kind.KUUDRA && r.level() == nextTier);
		rows.add(new Row("Kuudra", Theme.TEXT, kuudra < 0 ? "None" : kuudraName(kuudra), Theme.GOOD,
				nextKuudra == null ? "Every tier done!" : "Next: " + nextKuudra + (kuudraUnlocks.isEmpty() ? "" : "  •  unlocks " + summary(kuudraUnlocks)),
				Theme.MUTED, unlockTooltip(nextKuudra == null ? "Kuudra" : nextKuudra, kuudraUnlocks), null, false));
		int trophy = profile.trophyRewards.stream().mapToInt(Requirement.TROPHY_REWARDS::indexOf).max().orElse(-1);
		rows.add(new Row("Trophy Hunter", Theme.TEXT, trophy < 0 ? "None" : Requirement.pretty(Requirement.TROPHY_REWARDS.get(trophy)), Theme.GOOD,
				trophy + 1 < Requirement.TROPHY_REWARDS.size() ? "Next: " + Requirement.pretty(Requirement.TROPHY_REWARDS.get(trophy + 1)) + " (catch every trophy fish at that tier)" : "Diamond done!",
				Theme.MUTED, null, null, false));
		for (String faction : List.of("BARBARIANS", "MAGES")) {
			int rep = profile.reputation.getOrDefault(faction, 0);
			rows.add(new Row(Requirement.pretty(faction) + " reputation", Theme.TEXT, String.format("%,d", rep), Theme.COINS,
					"", 0, null, null, false));
		}
		return rows;
	}

	private static String kuudraName(int tier) {
		return tier == 0 ? "Basic" : Requirement.pretty(Requirement.KUUDRA_TIERS.get(tier));
	}

	/** Names of gear and accessories with a requirement matching {@code test}. */
	private static List<String> unlockedBy(java.util.function.Predicate<Requirement> test) {
		ItemDatabase db = ProgressionClient.get().database();
		Set<String> names = new LinkedHashSet<>();
		for (GearItem item : db.items()) {
			if (item.requirements().stream().anyMatch(test)) {
				names.add(item.name());
			}
		}
		for (ItemDatabase.Accessory a : db.accessories()) {
			if (a.requirements().stream().anyMatch(test)) {
				names.add(a.name());
			}
		}
		return new ArrayList<>(names);
	}

	private static String summary(List<String> names) {
		return names.size() == 1 ? names.get(0) : names.get(0) + " +" + (names.size() - 1) + " more";
	}

	private static List<Component> unlockTooltip(String what, List<String> names) {
		if (names.isEmpty()) {
			return null;
		}
		List<Component> lines = new ArrayList<>();
		lines.add(Component.literal(what + " unlocks:").withStyle(ChatFormatting.GOLD));
		for (String name : names.subList(0, Math.min(14, names.size()))) {
			lines.add(Component.literal("  " + name).withStyle(ChatFormatting.GRAY));
		}
		if (names.size() > 14) {
			lines.add(Component.literal("  ...and " + (names.size() - 14) + " more").withStyle(ChatFormatting.DARK_GRAY));
		}
		return lines;
	}

	// ---------------------------------------------------------------- accessories

	static List<Row> accessories(PlayerProfile profile) {
		List<Row> rows = new ArrayList<>();
		if (!profile.known.contains("ACCESSORIES")) {
			rows.add(Row.hint("Open every Accessory Bag page once."));
		}
		if (!ProgressionClient.get().auctions().hasPrices()) {
			rows.add(Row.hint("Click [Scan AH] above for auction prices."));
		}
		List<Advisor.AccessoryPick> picks = Advisor.missingAccessories(profile);
		List<Advisor.AccessoryPick> now = picks.stream().filter(Advisor.AccessoryPick::canGet).toList();
		if (profile.accessoriesByMp) {
			// Most MP first; among equals, the cheaper one per MP.
			now = now.stream().sorted(Comparator.comparingInt((Advisor.AccessoryPick p) -> -p.powerGain())
					.thenComparingDouble(Advisor.AccessoryPick::coinsPerPower)).toList();
		}
		List<Advisor.AccessoryPick> later = picks.stream().filter(p -> !p.canGet())
				.sorted(Comparator.comparingInt(p -> -p.powerGain())).toList();

		rows.add(Row.section("YOU CAN GET NOW  (" + now.size() + (profile.accessoriesByMp ? ", most Magical Power first)" : ", cheapest per Magical Power first)")));
		for (Advisor.AccessoryPick p : now.subList(0, Math.min(60, now.size()))) {
			rows.add(accessoryRow(p));
		}
		rows.add(Row.section("GO FOR NEXT  (" + later.size() + ", needs something first)"));
		for (Advisor.AccessoryPick p : later.subList(0, Math.min(60, later.size()))) {
			rows.add(accessoryRow(p));
		}
		return rows;
	}

	private static Row accessoryRow(Advisor.AccessoryPick p) {
		ItemDatabase db = ProgressionClient.get().database();
		String price = p.price() == null ? "no price" : ProgressionScreen.coins(p.price());
		String sub = "+" + p.powerGain() + " MP";
		if (p.price() != null && p.powerGain() > 0) {
			sub += "  •  " + ProgressionScreen.coins(p.coinsPerPower()) + " per MP";
		}
		if (p.replaces() != null) {
			sub += "  •  upgrades " + db.name(p.replaces());
		}
		if (!p.canGet()) {
			sub = "Needs " + String.join(", ", p.missing());
		}
		List<Component> tip = new ArrayList<>();
		tip.add(Component.literal(p.accessory().name()).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
		tip.add(Component.literal(Requirement.pretty(p.accessory().rarity()) + " accessory  •  +" + p.powerGain() + " Magical Power")
				.withStyle(ChatFormatting.GRAY));
		tip.add(Component.literal(p.price() == null ? "No bazaar/BIN price (craft it, or run Scan AH)" : "Cheapest buy: " + price)
				.withStyle(ChatFormatting.GREEN));
		for (String m : p.missing()) {
			tip.add(Component.literal("  ✖ " + m).withStyle(ChatFormatting.RED));
		}
		return new Row(p.accessory().name(), ProgressionScreen.rarityColor(p.accessory().rarity()), price,
				p.price() == null ? 0xFF777777 : Theme.COINS, sub, p.canGet() ? Theme.MUTED : 0xFFCC4444, tip, null, false)
				.withRecipe(p.accessory().id());
	}

	// ---------------------------------------------------------------- upgrades

	/** How you'd get an upgrade: "BEST" (cheaper of buying or crafting), "CRAFT" (craft/upgrade only) or "BUY". */
	static String wayLabel(String way) {
		return switch (way) {
			case "CRAFT" -> "Craft/Upgrade";
			case "BUY" -> "Buy";
			default -> "Cheapest way";
		};
	}

	/** True when crafting/upgrading is the way shown for this upgrade. */
	private static boolean useCraft(Advisor.Upgrade u, String way) {
		Advisor.Craft c = u.craft();
		if (c == null || c.loading()) {
			return false;
		}
		return switch (way) {
			case "CRAFT" -> true;
			case "BUY" -> false;
			default -> c.cost() != null && (u.cost() == null || c.cost() < u.cost());
		};
	}

	private static Double wayCost(Advisor.Upgrade u, String way) {
		return useCraft(u, way) ? u.craft().cost() : u.cost();
	}

	static List<Row> upgrades(PlayerProfile profile, boolean byImpact) {
		String way = profile.upgradeWay;
		List<Advisor.Upgrade> list = new ArrayList<>(Advisor.upgrades(profile));
		if (way.equals("CRAFT")) {
			// The filter: only things you can craft or upgrade into.
			list.removeIf(u -> u.craft() == null || u.craft().loading());
		}
		if (profile.onlyAffordable && profile.purse > 0) {
			list.removeIf(u -> wayCost(u, way) == null || wayCost(u, way) > profile.purse);
		}
		Comparator<Advisor.Upgrade> cheapest =Comparator.comparingDouble(u -> wayCost(u, way) == null ? Double.MAX_VALUE : wayCost(u, way));
		if (byImpact) {
			list.sort(Comparator.comparingDouble((Advisor.Upgrade u) -> Double.isNaN(u.impact()) ? -1 : -u.impact()).thenComparing(cheapest));
		} else {
			list.sort(cheapest);
		}
		List<Row> rows = new ArrayList<>();
		if (!ProgressionClient.get().auctions().hasPrices()) {
			rows.add(Row.hint("For gear prices, [Scan AH] on Accessories."));
		}
		if (profile.ownedItems.isEmpty()) {
			rows.add(Row.hint("Add an API key so gear you own is skipped."));
		}
		boolean loading = false;
		for (Advisor.Upgrade u : list) {
			Advisor.Craft c = u.craft();
			loading |= c != null && c.loading();
			boolean craft = useCraft(u, way);
			Double cost = wayCost(u, way);
			String right = (craft ? "Craft " : "") + (cost == null ? "no price" : ProgressionScreen.coins(cost));
			String sub;
			if (craft && c.usesOwnedItems()) {
				sub = "Upgrade from your " + String.join(", ", c.tradeIn());
			} else if (craft) {
				sub = "Craft it  •  " + u.detail();
			} else {
				sub = u.kind() + "  •  " + u.detail();
			}
			if (!Double.isNaN(u.impact())) {
				sub += String.format("  •  +%.0f%% better", u.impact() * 100);
			}

			List<Component> tip = new ArrayList<>();
			tip.add(Component.literal(u.title()).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
			tip.add(Component.literal(u.kind() + "  •  " + u.detail()).withStyle(ChatFormatting.GRAY));
			tip.add(Component.empty());
			tip.add(Component.literal("Buy: " + (u.cost() == null ? "no price" : ProgressionScreen.coins(u.cost())))
					.withStyle(craft ? ChatFormatting.GRAY : ChatFormatting.GREEN));
			if (c == null) {
				tip.add(Component.literal("Craft/upgrade: no recipe").withStyle(ChatFormatting.DARK_GRAY));
			} else if (c.loading()) {
				tip.add(Component.literal("Craft/upgrade: loading recipe...").withStyle(ChatFormatting.DARK_GRAY));
			} else {
				tip.add(Component.literal("Craft/upgrade: " + (c.cost() == null ? "some materials have no price" : ProgressionScreen.coins(c.cost())))
						.withStyle(craft ? ChatFormatting.GREEN : ChatFormatting.GRAY));
				for (String t : c.tradeIn()) {
					tip.add(Component.literal("  ✔ " + t + " (you have it)").withStyle(ChatFormatting.AQUA));
				}
				for (String m : c.materials()) {
					tip.add(Component.literal("  • " + m).withStyle(ChatFormatting.GRAY));
				}
			}
			if (cost != null && profile.purse > 0) {
				tip.add(Component.literal(profile.purse >= cost ? "You can afford this" : ProgressionScreen.coins(cost - profile.purse) + " short")
						.withStyle(profile.purse >= cost ? ChatFormatting.GREEN : ChatFormatting.RED));
			}
			rows.add(new Row(u.title(), Theme.TEXT, right, cost == null ? 0xFF777777 : craft ? Theme.GOOD : Theme.COINS, sub,
					craft && c.usesOwnedItems() ? 0xFF55FFFF : Theme.MUTED, tip, null, false)
					.withRecipe(u.itemId()));
		}
		if (loading) {
			rows.add(0, Row.hint("Loading recipes..."));
		}
		if (list.isEmpty()) {
			rows.add(Row.hint(profile.onlyAffordable && profile.purse > 0 ? "Nothing here fits your purse right now."
					: way.equals("CRAFT") ? "Nothing to craft or upgrade into right now." : "You own the best of everything you can use!"));
		}
		return rows;
	}

	// ---------------------------------------------------------------- museum

	/** Only this many of each list, to keep recipe downloads small. */
	private static final int MUSEUM_ROWS = 60;

	static List<Row> museum(PlayerProfile profile) {
		List<Row> rows = new ArrayList<>();
		if (!profile.known.contains("MUSEUM")) {
			rows.add(Row.hint("Add an API key to skip what you've donated."));
		}
		if (profile.ownedItems.isEmpty()) {
			rows.add(Row.hint("Add an API key so gear you own is found."));
		}
		List<Advisor.MuseumPick> picks = new ArrayList<>(Advisor.museumPicks(profile));
		if (profile.museumSetsOnly) {
			picks.removeIf(p -> !p.entry().set());
		}
		List<Advisor.MuseumPick> ready = picks.stream().filter(Advisor.MuseumPick::canDonate).toList();
		List<Advisor.MuseumPick> now = picks.stream().filter(p -> !p.canDonate() && p.canGet()).toList();
		List<Advisor.MuseumPick> later = picks.stream().filter(p -> !p.canDonate() && !p.canGet()).toList();

		rows.add(Row.section("YOU HAVE IT, DONATE IT  (" + ready.size() + ")"));
		for (Advisor.MuseumPick p : ready) {
			rows.add(museumRow(p, profile, false));
		}
		rows.add(Row.section("YOU CAN GET NOW  (" + now.size() + ", cheapest first)"));
		for (Advisor.MuseumPick p : now.subList(0, Math.min(MUSEUM_ROWS, now.size()))) {
			rows.add(museumRow(p, profile, true));
		}
		rows.add(Row.section("GO FOR NEXT  (" + later.size() + ", needs something first)"));
		for (Advisor.MuseumPick p : later.subList(0, Math.min(MUSEUM_ROWS, later.size()))) {
			rows.add(museumRow(p, profile, false));
		}
		return rows;
	}

	private static Row museumRow(Advisor.MuseumPick p, PlayerProfile profile, boolean withCraft) {
		ItemDatabase db = ProgressionClient.get().database();
		int total = p.entry().pieces().size();
		int have = total - p.missing().size();
		Advisor.Craft craft = withCraft ? Advisor.craft(profile, p.missing()) : null;
		boolean useCraft = craft != null && !craft.loading() && craft.cost() != null && (p.price() == null || craft.cost() < p.price());
		Double cost = useCraft ? craft.cost() : p.price();
		String right = p.canDonate() ? "Ready" : (useCraft ? "Craft " : "") + (cost == null ? "no price" : ProgressionScreen.coins(cost));
		int rightColor = p.canDonate() ? Theme.GOOD : cost == null ? 0xFF777777 : useCraft ? Theme.GOOD : Theme.COINS;

		String sub = Requirement.pretty(p.entry().category()) + "  •  " + p.entry().xp() + " SkyBlock XP";
		if (!p.canDonate() && !p.canGet()) {
			sub = "Needs " + String.join(", ", p.requirements());
		} else if (p.entry().set() && have > 0 && !p.canDonate()) {
			sub = "You have " + have + " of " + total + " pieces  •  " + sub;
		}

		List<Component> tip = new ArrayList<>();
		tip.add(Component.literal(p.name()).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
		tip.add(Component.literal(sub).withStyle(ChatFormatting.GRAY));
		if (p.entry().set()) {
			tip.add(Component.empty());
			for (String piece : p.entry().pieces()) {
				boolean missing = p.missing().contains(piece);
				tip.add(Component.literal((missing ? "  ✖ " : "  ✔ ") + db.name(piece)).withStyle(missing ? ChatFormatting.GRAY : ChatFormatting.GREEN));
			}
		}
		if (!p.canDonate()) {
			tip.add(Component.empty());
			tip.add(Component.literal("Buy: " + (p.price() == null ? "no price" : ProgressionScreen.coins(p.price()))).withStyle(ChatFormatting.GRAY));
			if (craft != null && !craft.loading()) {
				tip.add(Component.literal("Craft/upgrade: " + (craft.cost() == null ? "some materials have no price" : ProgressionScreen.coins(craft.cost()))).withStyle(ChatFormatting.GRAY));
				for (String t : craft.tradeIn()) {
					tip.add(Component.literal("  ✔ " + t + " (you have it)").withStyle(ChatFormatting.AQUA));
				}
				for (String m : craft.materials()) {
					tip.add(Component.literal("  • " + m).withStyle(ChatFormatting.GRAY));
				}
			}
		}
		String recipe = p.missing().isEmpty() ? p.entry().pieces().get(0) : p.missing().get(0);
		return new Row(p.name(), p.canDonate() ? Theme.GOOD : Theme.TEXT, right, rightColor, sub,
				p.canGet() ? Theme.MUTED : 0xFFCC4444, tip, null, false).withRecipe(recipe);
	}

	// ---------------------------------------------------------------- minions

	static List<Row> minions(PlayerProfile profile) {
		MinionTracker tracker = ProgressionClient.get().minions();
		List<Row> rows = new ArrayList<>();
		if (profile.minions.isEmpty()) {
			rows.add(Row.hint("Open each of your minions once and they'll show up here."));
			return rows;
		}
		double total = 0;
		List<Row> minionRows = new ArrayList<>();
		for (PlayerProfile.MinionState m : profile.minions) {
			MinionTracker.Estimate e = tracker.estimate(m);
			if (!Double.isNaN(e.coinsPerHour())) {
				total += e.coinsPerHour();
			}
			String right;
			int rightColor;
			if (e.fullAtMillis() < 0) {
				right = "?";
				rightColor = 0xFF777777;
			} else {
				double hours = (e.fullAtMillis() - System.currentTimeMillis()) / 3_600_000.0;
				right = hours <= 0 ? "FULL" : "full in " + Advisor.hours(hours);
				rightColor = hours <= 0 ? Theme.BAD : hours < 2 ? Theme.COINS : Theme.GOOD;
			}
			StringBuilder sub = new StringBuilder();
			if (Double.isNaN(e.itemsPerHour())) {
				sub.append("Loading minion data...");
			} else {
				sub.append(String.format("%.0f items/h", e.itemsPerHour()));
				if (!Double.isNaN(e.coinsPerHour())) {
					sub.append("  •  ").append(ProgressionScreen.coins(e.coinsPerHour())).append("/h");
				}
				if (!Double.isNaN(e.nextTierCost())) {
					sub.append("  •  next tier ").append(ProgressionScreen.coins(e.nextTierCost()));
					if (!Double.isNaN(e.hoursToPayNextTier())) {
						sub.append(" (pays back in ").append(Advisor.hours(e.hoursToPayNextTier())).append(")");
					}
				}
			}
			String title = Requirement.pretty(m.type) + " Minion " + m.tier + "  " + m.stored + "/" + (m.capacity > 0 ? m.capacity : "?");
			List<Component> tip = List.of(
					Component.literal(Requirement.pretty(m.type) + " Minion " + m.tier).withStyle(ChatFormatting.GOLD),
					Component.literal(sub.toString()).withStyle(ChatFormatting.GREEN),
					Component.literal("At " + m.x + ", " + m.y + ", " + m.z).withStyle(ChatFormatting.GRAY),
					Component.literal("Estimates assume no fuel or upgrades,").withStyle(ChatFormatting.DARK_GRAY),
					Component.literal("selling to the bazaar. Right-click to forget it.").withStyle(ChatFormatting.DARK_GRAY));
			minionRows.add(new Row(title, Theme.TEXT, right, rightColor, sub.toString(), Theme.MUTED, tip, () -> {
				profile.minions.remove(m);
				ProgressionClient.get().profileChanged();
			}, false).withRecipe(m.type + "_GENERATOR_" + (Double.isNaN(e.nextTierCost()) ? m.tier : m.tier + 1)));
		}
		rows.add(Row.section("TOTAL  ≈ " + ProgressionScreen.coins(total) + " coins/hour from " + profile.minions.size() + " minions"));
		rows.addAll(minionRows);
		return rows;
	}
}
