package dev.sw1ftz.progression;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Picks the best gear you can use right now and the next upgrade, for one slot of one skill.
 * Items are ranked by Hypixel's own progression stage first, then by the stats that matter for the skill.
 */
public final class Recommender {
	private Recommender() {
	}

	/** One choice: a single item, or a whole armor set. */
	public record Option(String name, String rarity, int stage, double score, List<GearItem> pieces,
						 List<Requirement> requirements, Map<String, Double> stats) {
		public double rank() {
			return stage * 1_000_000.0 + score * 10 + GearItem.rarityIndex(rarity);
		}

		public String stageName() {
			return stage >= 0 ? GearItem.STAGES[stage] : "Unranked";
		}

		/** Requirements you haven't met (or that the guide can't confirm you've met). */
		public List<String> missing(PlayerProfile profile) {
			List<String> out = new ArrayList<>();
			for (Requirement r : requirements) {
				if (!profile.meets(r)) {
					out.add(r.text());
				}
			}
			return out;
		}

		public boolean unlocked(PlayerProfile profile) {
			return missing(profile).isEmpty();
		}

		/** Same with an extra requirement attached. */
		Option withRequirement(Requirement extra) {
			List<Requirement> all = new ArrayList<>(requirements);
			all.add(extra);
			return new Option(name, rarity, stage, score, pieces, List.copyOf(all), stats);
		}
	}

	/**
	 * The result for one slot. {@code best} and {@code next} can be null.
	 * {@code similar} lists other unlocked choices that are just as good (e.g. Mk. III tools for other crops).
	 */
	public record Pick(SkillCategory.Slot slot, Option best, Option next, List<String> similar, int choices) {
	}

	/**
	 * Lowest level we suggest for each of Hypixel's stages (Starter ... Master). Many late items have
	 * no level requirement and are gated by cost instead, so without this a new player would be told to
	 * use endgame gear.
	 */
	private static final int[] SUGGESTED_LEVEL = {0, 0, 5, 12, 20, 30, 45};

	public static List<Pick> recommend(SkillCategory skill, List<GearItem> items, PlayerProfile profile) {
		List<Pick> picks = new ArrayList<>();
		for (SkillCategory.Slot slot : skill.slots) {
			picks.add(pick(slot, options(skill, slot, items), profile));
		}
		return picks;
	}

	/** Every choice for one slot of one skill, with suggested levels attached. */
	public static List<Option> options(SkillCategory skill, SkillCategory.Slot slot, List<GearItem> items) {
		Map<String, Double> weights = slot.isArmor() ? skill.armorWeights : skill.toolWeights;
		List<GearItem> pool = items.stream()
				.filter(i -> i.area().equals(skill.area) && slot.categories().contains(i.category()))
				.toList();
		return (slot.isArmor()
				? armorSets(pool, weights)
				: pool.stream().map(i -> single(i, weights)).toList())
				.stream()
				// Armor without any of this skill's stats (e.g. a plain Health set in the farming museum) isn't farming armor.
				.filter(o -> !slot.isArmor() || weights.isEmpty() || o.score() > 0)
				.map(o -> withSuggestedLevel(o, skill))
				.toList();
	}

	private static Option withSuggestedLevel(Option option, SkillCategory skill) {
		if (option.stage() < 0) {
			return option;
		}
		int suggested = SUGGESTED_LEVEL[option.stage()];
		boolean covered = option.requirements().stream().anyMatch(r -> r.level() >= suggested
				&& ((r.kind() == Requirement.Kind.SKILL && r.key().equals(skill.levelKey))
				|| (r.kind() == Requirement.Kind.CATACOMBS_LEVEL && skill.levelKey.equals("CATACOMBS"))));
		if (suggested == 0 || covered) {
			return option;
		}
		String name = skill.levelKey.equals("CATACOMBS") ? "Catacombs" : skill.title;
		return option.withRequirement(new Requirement(Requirement.Kind.SKILL, skill.levelKey, suggested,
				name + " " + suggested + " (suggested)", List.of()));
	}

	private static Pick pick(SkillCategory.Slot slot, List<Option> options, PlayerProfile profile) {
		Comparator<Option> byRank = Comparator.comparingDouble(Option::rank);
		Option best = options.stream().filter(o -> o.unlocked(profile)).max(byRank).orElse(null);
		double floor = best == null ? Double.NEGATIVE_INFINITY : best.rank();
		Option next = options.stream()
				.filter(o -> !o.unlocked(profile) && o.rank() > floor)
				.min(byRank)
				.orElse(null);
		List<String> similar = best == null ? List.of() : options.stream()
				.filter(o -> o != best && o.unlocked(profile) && o.rank() == best.rank())
				.map(Option::name)
				.sorted()
				.toList();
		return new Pick(slot, best, next, similar, options.size());
	}

	private static Option single(GearItem item, Map<String, Double> weights) {
		return new Option(item.name(), item.rarity(), item.stage(), score(item.stats(), weights), List.of(item),
				item.requirements(), item.stats());
	}

	/** Groups armor pieces into sets; pieces that aren't part of a set of 2+ are offered on their own. */
	private static List<Option> armorSets(List<GearItem> pool, Map<String, Double> weights) {
		Map<String, List<GearItem>> sets = new LinkedHashMap<>();
		for (GearItem item : pool) {
			sets.computeIfAbsent(item.setId() != null ? item.setId() : "#" + item.id(), k -> new ArrayList<>()).add(item);
		}
		List<Option> grouped = new ArrayList<>();
		List<Option> singles = new ArrayList<>();
		for (List<GearItem> pieces : sets.values()) {
			if (pieces.size() < 2) {
				singles.add(single(pieces.get(0), weights));
				continue;
			}
			pieces.sort(Comparator.comparingInt(p -> slotOrder(p.category())));
			Map<String, Double> stats = new HashMap<>();
			// Pieces often ask for different levels of the same thing; keep only the highest of each.
			Map<String, Requirement> requirements = new LinkedHashMap<>();
			int stage = -1;
			String rarity = "COMMON";
			for (GearItem piece : pieces) {
				piece.stats().forEach((k, v) -> stats.merge(k, v, Double::sum));
				for (Requirement r : piece.requirements()) {
					String key = r.kind() == Requirement.Kind.OTHER || r.kind() == Requirement.Kind.ANY_OF
							? r.kind() + ":" + r.text() : r.kind() + ":" + r.key();
					requirements.merge(key, r, (a, b) -> a.level() >= b.level() ? a : b);
				}
				stage = Math.max(stage, piece.stage());
				if (GearItem.rarityIndex(piece.rarity()) > GearItem.rarityIndex(rarity)) {
					rarity = piece.rarity();
				}
			}
			grouped.add(new Option(setName(pieces), rarity, stage, score(stats, weights), List.copyOf(pieces),
					List.copyOf(requirements.values()), Map.copyOf(stats)));
		}
		// Prefer real sets; only fall back to loose pieces when a skill has no sets at all.
		return grouped.isEmpty() ? singles : grouped;
	}

	private static int slotOrder(String category) {
		return switch (category) {
			case "HELMET" -> 0;
			case "CHESTPLATE" -> 1;
			case "LEGGINGS" -> 2;
			default -> 3;
		};
	}

	private static String setName(List<GearItem> pieces) {
		GearItem chest = pieces.stream().filter(p -> p.category().equals("CHESTPLATE")).findFirst().orElse(pieces.get(0));
		String name = chest.name()
				.replaceAll("^Chestplate of (the )?", "")
				.replaceAll("(?i) (Chestplate|Tunic|Shirt|Jacket|Vest|Helmet|Leggings|Boots|Hat|Hood|Cap)\\b", "")
				.trim();
		// "Perfect - Tier XII" -> "Perfect Armor - Tier XII"
		int dash = name.indexOf(" - ");
		if (dash > 0) {
			return name.substring(0, dash) + " Armor" + name.substring(dash);
		}
		return name.endsWith("Armor") ? name : name + " Armor";
	}

	private static double score(Map<String, Double> stats, Map<String, Double> weights) {
		double score = 0;
		for (Map.Entry<String, Double> w : weights.entrySet()) {
			score += stats.getOrDefault(w.getKey(), 0.0) * w.getValue();
		}
		return score;
	}

	/** The two most important stats for this slot, e.g. "+120 Farming Fortune, +45 Bonus Pest Chance". */
	public static String keyStats(Option option, Map<String, Double> weights) {
		List<Map.Entry<String, Double>> ranked = new ArrayList<>();
		for (String key : weights.keySet()) {
			double value = option.stats().getOrDefault(key, 0.0);
			if (value != 0) {
				ranked.add(Map.entry(key, value * weights.get(key)));
			}
		}
		ranked.sort(Map.Entry.<String, Double>comparingByValue().reversed());
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < Math.min(2, ranked.size()); i++) {
			String key = ranked.get(i).getKey();
			if (!out.isEmpty()) {
				out.append(", ");
			}
			out.append("+").append(number(option.stats().get(key))).append(' ').append(Requirement.pretty(key));
		}
		return out.toString();
	}

	public static String number(double value) {
		return value == Math.floor(value) ? String.valueOf((long) value) : String.format(Locale.ROOT, "%.1f", value);
	}
}
