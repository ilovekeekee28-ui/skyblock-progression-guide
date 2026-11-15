package dev.sw1ftz.progression;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Works out missing accessories and the best next upgrades from everything the guide knows. */
public final class Advisor {
	/** How many recipes deep to look for trade-ins and prices. */
	private static final int MAX_RECIPE_DEPTH = 4;

	private Advisor() {
	}

	/** Magical Power each accessory rarity gives. */
	public static int magicalPower(String rarity) {
		return switch (rarity) {
			case "UNCOMMON" -> 5;
			case "RARE" -> 8;
			case "EPIC" -> 12;
			case "LEGENDARY" -> 16;
			case "MYTHIC", "DIVINE" -> 22;
			case "VERY_SPECIAL" -> 5;
			default -> 3; // COMMON, SPECIAL
		};
	}

	/** Cheapest way to buy one: bazaar instant-buy, else lowest auction BIN. Null if unknown. */
	public static Double buyPrice(String id) {
		ProgressionClient client = ProgressionClient.get();
		Double bazaar = client.prices().buyPrice(id);
		return bazaar != null ? bazaar : client.auctions().lowestBin(id);
	}

	// ---------------------------------------------------------------- accessories

	/**
	 * One accessory you don't have yet.
	 *
	 * @param replaces     the owned accessory it upgrades, or null
	 * @param powerGain    Magical Power you'd gain
	 * @param price        cost to buy, or null if unknown
	 * @param canGet       you meet all its requirements we can check
	 * @param missing      requirements you don't meet (or that we have no data for)
	 */
	public record AccessoryPick(ItemDatabase.Accessory accessory, String replaces, int powerGain, Double price,
								boolean canGet, List<String> missing) {
		/** Coins per Magical Power, lower is better. */
		public double coinsPerPower() {
			return price == null || powerGain <= 0 ? Double.MAX_VALUE : price / powerGain;
		}
	}

	public static List<AccessoryPick> missingAccessories(PlayerProfile profile) {
		ProgressionClient client = ProgressionClient.get();
		Map<String, List<String>> upgrades = client.repo().accessoryUpgrades();
		Set<String> ignored = client.repo().ignoredAccessories();
		Map<String, ItemDatabase.Accessory> byId = new java.util.HashMap<>();
		for (ItemDatabase.Accessory a : client.database().accessories()) {
			byId.put(a.id(), a);
		}

		List<AccessoryPick> out = new ArrayList<>();
		for (ItemDatabase.Accessory accessory : client.database().accessories()) {
			String id = accessory.id();
			if (ignored.contains(id) || profile.accessories.contains(id) || covered(id, profile.accessories, upgrades)) {
				continue;
			}
			// Only the highest tier of each line: skip it if a higher tier is listed too.
			if (hasListedUpgrade(id, profile.accessories, ignored, byId, upgrades)) {
				continue;
			}
			// If you own a lower tier of this one, it's an upgrade of that.
			String replaces = null;
			int replacedPower = 0;
			for (String owned : profile.accessories) {
				if (covered(owned, Set.of(id), upgrades)) {
					ItemDatabase.Accessory lower = byId.get(owned);
					int power = lower == null ? 0 : magicalPower(lower.rarity());
					if (replaces == null || power > replacedPower) {
						replaces = owned;
						replacedPower = power;
					}
				}
			}
			List<String> missing = new ArrayList<>();
			for (Requirement r : accessory.requirements()) {
				if (!profile.meets(r)) {
					missing.add(r.text());
				}
			}
			out.add(new AccessoryPick(accessory, replaces, magicalPower(accessory.rarity()) - replacedPower,
					buyPrice(id), missing.isEmpty(), missing));
		}
		// Priced ones cheapest per MP first; unpriced ones lowest rarity first, as those are usually cheap to craft.
		out.sort(Comparator.comparing((AccessoryPick p) -> !p.canGet())
				.thenComparingDouble(AccessoryPick::coinsPerPower)
				.thenComparingInt(AccessoryPick::powerGain));
		return out;
	}

	/** True if some higher tier of this accessory would also be suggested. */
	private static boolean hasListedUpgrade(String id, Set<String> owned, Set<String> ignored,
			Map<String, ItemDatabase.Accessory> byId, Map<String, List<String>> upgrades) {
		for (String next : upgrades.getOrDefault(id, List.of())) {
			if (byId.containsKey(next) && !ignored.contains(next) && !owned.contains(next)) {
				return true;
			}
		}
		return false;
	}

	/** True if you own this accessory or anything that replaces it. */
	private static boolean covered(String id, Set<String> owned, Map<String, List<String>> upgrades) {
		Set<String> seen = new HashSet<>();
		List<String> queue = new ArrayList<>(upgrades.getOrDefault(id, List.of()));
		while (!queue.isEmpty()) {
			String next = queue.remove(queue.size() - 1);
			if (!seen.add(next)) {
				continue;
			}
			if (owned.contains(next)) {
				return true;
			}
			queue.addAll(upgrades.getOrDefault(next, List.of()));
		}
		return false;
	}

	public static int totalMagicalPower(PlayerProfile profile) {
		int total = 0;
		for (ItemDatabase.Accessory a : ProgressionClient.get().database().accessories()) {
			if (profile.accessories.contains(a.id())) {
				total += magicalPower(a.rarity());
			}
		}
		return total;
	}

	// ---------------------------------------------------------------- museum

	/**
	 * Something you haven't donated yet.
	 *
	 *  missing      pieces you don't have
	 *  price        cost to buy the missing pieces, or null if one has no price
	 *  requirements requirements of the pieces you don't meet
	 */
	public record MuseumPick(RepoData.MuseumEntry entry, String name, List<String> missing, Double price, List<String> requirements) {
		public boolean canDonate() {
			return missing.isEmpty();
		}

		public boolean canGet() {
			return requirements.isEmpty();
		}
	}

	/** Everything the museum counts as donated: what you gave, plus the lower tiers those cover. */
	public static Set<String> museumDonated(PlayerProfile profile) {
		Map<String, String> children = ProgressionClient.get().repo().museumChildren();
		Set<String> donated = new HashSet<>();
		for (String id : profile.museum) {
			String next = id;
			while (next != null && donated.add(next)) {
				next = children.get(next);
			}
		}
		return donated;
	}

	/** What you haven't donated, cheapest first. */
	public static List<MuseumPick> museumPicks(PlayerProfile profile) {
		ProgressionClient client = ProgressionClient.get();
		Map<String, GearItem> gear = new java.util.HashMap<>();
		for (GearItem item : client.database().items()) {
			gear.put(item.id(), item);
		}
		Set<String> owned = new HashSet<>();
		Map<String, String> mapped = client.repo().museumMapped();
		for (String id : profile.ownedItems) {
			owned.add(mapped.getOrDefault(id, id));
		}
		Set<String> donated = museumDonated(profile);
		List<MuseumPick> out = new ArrayList<>();
		for (RepoData.MuseumEntry entry : client.repo().museumEntries()) {
			if (donated.contains(entry.id())) {
				continue;
			}
			List<String> missing = entry.pieces().stream().filter(p -> !owned.contains(p)).toList();
			Double price = 0.0;
			for (String piece : missing) {
				Double each = buyPrice(piece);
				price = price == null || each == null ? null : Double.valueOf(price + each);
			}
			Set<String> requirements = new java.util.LinkedHashSet<>();
			for (String piece : missing) {
				GearItem item = gear.get(piece);
				if (item != null) {
					for (Requirement r : item.requirements()) {
						if (!profile.meets(r)) {
							requirements.add(r.text());
						}
					}
				}
			}
			String name = entry.set() ? Requirement.pretty(entry.id()) + " Set" : client.database().name(entry.id());
			out.add(new MuseumPick(entry, name, missing, price, List.copyOf(requirements)));
		}
		out.sort(Comparator.comparingDouble(p -> p.price() == null ? Double.MAX_VALUE : p.price()));
		return out;
	}

	// ---------------------------------------------------------------- upgrades

	/**
	 * A suggested next upgrade.
	 *
	 * @param impact how much better it is, as a fraction (0.25 = 25% better), or NaN when unknown
	 */
	public record Upgrade(String kind, String title, String detail, Double cost, double impact, String itemId, Craft craft) {
		public Upgrade(String kind, String title, String detail, Double cost, double impact, String itemId) {
			this(kind, title, detail, cost, impact, itemId, null);
		}
	}

	/**
	 * Making it yourself instead of buying it: coins for the materials you don't have, plus the items you
	 * already own that the recipe uses up (e.g. your old armor piece). {@code cost} is null when a material
	 * has no price; {@code loading} while recipes are still downloading.
	 */
	public record Craft(Double cost, List<String> tradeIn, List<String> materials, boolean loading) {
		public boolean usesOwnedItems() {
			return !tradeIn.isEmpty();
		}
	}

	/** Craft/upgrade route for these items, or null when none of them has a recipe. */
	public static Craft craft(PlayerProfile profile, List<String> ids) {
		RepoData repo = ProgressionClient.get().repo();
		ItemDatabase db = ProgressionClient.get().database();
		Map<String, Double> needed = new java.util.LinkedHashMap<>();
		Set<String> used = new java.util.LinkedHashSet<>();
		boolean[] waiting = {false};
		boolean loading = false;
		boolean any = false;
		for (String id : ids) {
			Map<String, Double> recipe = repo.recipe(id);
			if (recipe == null) {
				loading = true;
				continue;
			}
			if (recipe.isEmpty()) {
				// No recipe: this piece has to be bought.
				needed.merge(id, 1.0, Double::sum);
				continue;
			}
			any = true;
			recipe.forEach((k, v) -> expand(k, v, 1, profile, needed, used, waiting));
		}
		loading |= waiting[0];
		if (!any) {
			return loading ? new Craft(null, List.of(), List.of(), true) : null;
		}
		Double cost = 0.0;
		List<String> tradeIn = new ArrayList<>();
		for (String id : used) {
			tradeIn.add(db.name(id));
		}
		List<String> materials = new ArrayList<>();
		for (Map.Entry<String, Double> e : needed.entrySet()) {
			String id = e.getKey();
			double amount = e.getValue();
			if (id.equals("SKYBLOCK_COIN")) {
				if (cost != null) {
					cost += amount;
				}
				continue;
			}
			String label = (amount == 1 ? "" : String.format(java.util.Locale.ROOT, "%,.0fx ", amount)) + db.name(id);
			materials.add(label);
			Double price = buyPrice(id);
			if (price == null) {
				cost = null;
			} else if (cost != null) {
				cost += price * amount;
			}
		}
		return new Craft(cost, tradeIn, materials, loading);
	}

	/**
	 * Adds an ingredient to the shopping list. Single items you own are used as they are (we only know that you
	 * own one, not how many, so stacks are still paid for). An ingredient is broken into its own recipe when it
	 * has no price, or when that recipe leads back to gear you own (your armor -> middle tier -> this one).
	 */
	private static void expand(String id, double amount, int depth, PlayerProfile profile, Map<String, Double> needed,
							   Set<String> used, boolean[] waiting) {
		if (!id.equals("SKYBLOCK_COIN")) {
			if (amount == 1 && ownsItem(profile, id) && used.add(id)) {
				return;
			}
			boolean unpriced = buyPrice(id) == null;
			if (depth < MAX_RECIPE_DEPTH && (unpriced || amount == 1)) {
				Map<String, Double> recipe = ProgressionClient.get().repo().recipe(id);
				if (recipe == null) {
					waiting[0] |= unpriced;
				} else if (!recipe.isEmpty() && (unpriced || leadsToOwned(recipe, depth + 1, profile))) {
					recipe.forEach((k, v) -> expand(k, v * amount, depth + 1, profile, needed, used, waiting));
					return;
				}
			}
		}
		needed.merge(id, amount, Double::sum);
	}

	private static boolean leadsToOwned(Map<String, Double> recipe, int depth, PlayerProfile profile) {
		for (Map.Entry<String, Double> e : recipe.entrySet()) {
			if (e.getValue() != 1 || e.getKey().equals("SKYBLOCK_COIN")) {
				continue;
			}
			if (ownsItem(profile, e.getKey())) {
				return true;
			}
			if (depth < MAX_RECIPE_DEPTH) {
				Map<String, Double> next = ProgressionClient.get().repo().recipe(e.getKey());
				if (next != null && leadsToOwned(next, depth + 1, profile)) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean ownsItem(PlayerProfile profile, String id) {
		return profile.ownedItems.contains(id) || profile.accessories.contains(id);
	}

	/** The item to show the recipe of: the chestplate for an armor set, else the item itself. */
	public static String recipeId(Recommender.Option option) {
		return option.pieces().stream().filter(p -> p.category().equals("CHESTPLATE")).findFirst()
				.orElse(option.pieces().get(0)).id();
	}

	public static List<Upgrade> upgrades(PlayerProfile profile) {
		ProgressionClient client = ProgressionClient.get();
		List<Upgrade> out = new ArrayList<>();

		// Gear: the best item you can use in each slot, if you don't own it yet.
		if (client.database().ready()) {
			for (SkillCategory skill : SkillCategory.values()) {
				for (SkillCategory.Slot slot : skill.slots) {
					List<Recommender.Option> options = Recommender.options(skill, slot, client.database().items());
					Recommender.Option best = options.stream().filter(o -> o.unlocked(profile))
							.max(Comparator.comparingDouble(Recommender.Option::rank)).orElse(null);
					if (best == null) {
						continue;
					}
					// Only the pieces you still need, so a half-finished set still shows up.
					List<GearItem> missing = best.pieces().stream().filter(p -> !profile.ownedItems.contains(p.id())).toList();
					if (missing.isEmpty()) {
						continue;
					}
					Recommender.Option current = options.stream().filter(o -> owns(profile, o))
							.max(Comparator.comparingDouble(Recommender.Option::rank)).orElse(null);
					Double cost = 0.0;
					for (GearItem piece : missing) {
						Double price = buyPrice(piece.id());
						if (price == null) {
							cost = null;
							break;
						}
						cost += price;
					}
					boolean finishing = current == best;
					double impact = current == null || finishing ? Double.NaN
							: current.score() > 0 ? (best.score() - current.score()) / current.score() : Double.NaN;
					String from = finishing ? missing.size() + " of " + best.pieces().size() + " pieces left"
							: current == null ? "nothing owned yet" : "replaces " + current.name();
					Craft craft = craft(profile, missing.stream().map(GearItem::id).toList());
					String recipe = missing.stream().filter(p -> p.category().equals("CHESTPLATE")).findFirst().orElse(missing.get(0)).id();
					out.add(new Upgrade(skill.title + " " + slot.label().toLowerCase(), best.name(), from, cost, impact, recipe, craft));
				}
			}
		}

		// Accessories: the five cheapest per Magical Power that you can get.
		missingAccessories(profile).stream()
				.filter(p -> p.canGet() && p.price() != null && p.powerGain() > 0)
				.limit(5)
				.forEach(p -> out.add(new Upgrade("Accessory", p.accessory().name(),
						"+" + p.powerGain() + " Magical Power" + (p.replaces() == null ? "" : " (upgrades " + client.database().name(p.replaces()) + ")"),
						p.price(), Double.NaN, p.accessory().id())));

		// Minions: the next tier, with how long its income takes to pay it back.
		for (PlayerProfile.MinionState minion : profile.minions) {
			MinionTracker.Estimate e = client.minions().estimate(minion);
			if (!Double.isNaN(e.nextTierCost())) {
				String payback = Double.isNaN(e.hoursToPayNextTier()) ? "" : ", pays for itself in " + hours(e.hoursToPayNextTier());
				out.add(new Upgrade("Minion", Requirement.pretty(minion.type) + " Minion " + (minion.tier + 1),
						"from tier " + minion.tier + payback, e.nextTierCost(), Double.NaN,
						minion.type + "_GENERATOR_" + (minion.tier + 1)));
			}
		}
		return out;
	}

	private static boolean owns(PlayerProfile profile, Recommender.Option option) {
		return option.pieces().stream().anyMatch(p -> profile.ownedItems.contains(p.id()));
	}

	public static String hours(double hours) {
		if (hours < 1) {
			return Math.max(1, Math.round(hours * 60)) + "m";
		}
		if (hours < 48) {
			return String.format(java.util.Locale.ROOT, "%.1fh", hours);
		}
		return String.format(java.util.Locale.ROOT, "%.1fd", hours / 24);
	}
}
