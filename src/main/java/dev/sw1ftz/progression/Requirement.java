package dev.sw1ftz.progression;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A requirement from Hypixel's item data. Skills, HOTM, Garden level and Catacombs are checked against
 * your levels. Everything else (slayers, collections, reputation, ...) can't be checked, so it is shown
 * as "also needs" instead of locking the item.
 */
public record Requirement(Kind kind, String key, int level, String text, List<Requirement> anyOf) {
	public enum Kind { SKILL, HOTM, GARDEN, CATACOMBS_LEVEL, CATACOMBS_FLOOR, SLAYER, COLLECTION, KUUDRA, TROPHY, REPUTATION, ANY_OF, OTHER }

	/** Kuudra tiers in order; the API calls the first one "none". */
	public static final List<String> KUUDRA_TIERS = List.of("NONE", "HOT", "BURNING", "FIERY", "INFERNAL");
	public static final List<String> TROPHY_REWARDS = List.of("BRONZE", "SILVER", "GOLD", "DIAMOND");

	public static Requirement parse(JsonObject o) {
		String type = o.get("type").getAsString();
		switch (type) {
			case "SKILL" -> {
				String skill = o.get("skill").getAsString();
				int level = o.get("level").getAsInt();
				return new Requirement(Kind.SKILL, skill, level, pretty(skill) + " " + level, List.of());
			}
			case "HEART_OF_THE_MOUNTAIN" -> {
				int tier = o.get("tier").getAsInt();
				return new Requirement(Kind.HOTM, "HOTM", tier, "HOTM " + tier, List.of());
			}
			case "GARDEN_LEVEL" -> {
				int level = o.get("level").getAsInt();
				return new Requirement(Kind.GARDEN, "GARDEN", level, "Garden " + level, List.of());
			}
			case "DUNGEON_SKILL" -> {
				int level = o.get("level").getAsInt();
				return new Requirement(Kind.CATACOMBS_LEVEL, "CATACOMBS", level, "Catacombs " + level, List.of());
			}
			case "DUNGEON_TIER" -> {
				int tier = o.get("tier").getAsInt();
				return new Requirement(Kind.CATACOMBS_FLOOR, "FLOOR", tier, "Floor " + tier + " completed", List.of());
			}
			case "SLAYER" -> {
				String boss = o.get("slayer_boss_type").getAsString().toLowerCase(Locale.ROOT);
				int level = o.get("level").getAsInt();
				return new Requirement(Kind.SLAYER, boss, level, describeOther(type, o), List.of());
			}
			case "COLLECTION" -> {
				return new Requirement(Kind.COLLECTION, o.get("collection").getAsString(), o.get("tier").getAsInt(),
						describeOther(type, o), List.of());
			}
			case "KUUDRA_COMPLETION" -> {
				String tier = o.get("kuudra_tier").getAsString().toUpperCase(Locale.ROOT);
				return new Requirement(Kind.KUUDRA, tier, KUUDRA_TIERS.indexOf(tier), describeOther(type, o), List.of());
			}
			case "TROPHY_FISHING" -> {
				String reward = o.get("reward").getAsString().toUpperCase(Locale.ROOT);
				return new Requirement(Kind.TROPHY, reward, TROPHY_REWARDS.indexOf(reward), describeOther(type, o), List.of());
			}
			case "CRIMSON_ISLE_REPUTATION" -> {
				return new Requirement(Kind.REPUTATION, o.get("faction").getAsString().toUpperCase(Locale.ROOT),
						o.get("reputation").getAsInt(), describeOther(type, o), List.of());
			}
			case "ANY_OF", "ONE_OF" -> {
				List<Requirement> options = new ArrayList<>();
				for (JsonElement e : o.getAsJsonArray("requirements")) {
					Requirement r = parse(e.getAsJsonObject());
					// Ironman/Stranded profile types are not something we track; skip them as options.
					if (!r.text.startsWith("Profile type")) {
						options.add(r);
					}
				}
				String text = options.isEmpty() ? "special profile" : options.get(0).text;
				return new Requirement(Kind.ANY_OF, "", 0, text, List.copyOf(options));
			}
			default -> {
				return new Requirement(Kind.OTHER, type, 0, describeOther(type, o), List.of());
			}
		}
	}

	private static String describeOther(String type, JsonObject o) {
		return switch (type) {
			case "SLAYER" -> pretty(o.get("slayer_boss_type").getAsString()) + " Slayer " + o.get("level").getAsInt();
			case "COLLECTION" -> pretty(o.get("collection").getAsString()) + " collection " + o.get("tier").getAsInt();
			case "CRIMSON_ISLE_REPUTATION" -> String.format(Locale.ROOT, "%,d %s rep", o.get("reputation").getAsInt(),
					pretty(o.get("faction").getAsString()));
			case "KUUDRA_COMPLETION" -> "Kuudra " + pretty(o.get("kuudra_tier").getAsString().replace("NONE", "Basic")) + " clear";
			case "TROPHY_FISHING" -> pretty(o.get("reward").getAsString()) + " Trophy Hunter";
			case "DUNGEON_BOSS_COLLECTION" -> "Floor " + o.get("tier").getAsInt() + " boss collection";
			case "PROFILE_TYPE" -> "Profile type " + pretty(o.get("profile_type").getAsString());
			case "SKILL_AVERAGE" -> "Skill average";
			default -> pretty(type);
		};
	}

	public static String pretty(String raw) {
		StringBuilder out = new StringBuilder();
		for (String word : raw.toLowerCase(Locale.ROOT).split("_")) {
			if (word.isEmpty()) {
				continue;
			}
			if (!out.isEmpty()) {
				out.append(' ');
			}
			out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
		}
		return out.toString();
	}
}
