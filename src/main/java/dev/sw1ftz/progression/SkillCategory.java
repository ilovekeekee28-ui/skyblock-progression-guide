package dev.sw1ftz.progression;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A tab in the guide. Each tab looks at items from one part of the game (Hypixel's museum category)
 * and ranks them with the stats that matter for it.
 */
public enum SkillCategory {
	FARMING("Farming", "FARMING", "FARMING",
			List.of(Slot.armor(), new Slot("Tool", Set.of("FARMING_TOOL")), new Slot("Pest Vacuum", Set.of("VACUUM"))),
			Map.of("farming_fortune", 1.0, "bonus_pest_chance", 0.4),
			Map.of("farming_fortune", 1.0)),
	MINING("Mining", "MINING", "MINING",
			List.of(Slot.armor(), new Slot("Tool", Set.of("PICKAXE", "DRILL", "GAUNTLET"))),
			Map.of("mining_fortune", 1.0, "mining_speed", 0.1, "block_fortune", 0.5, "gemstone_fortune", 0.5),
			Map.of("mining_speed", 0.1, "mining_fortune", 1.0, "gemstone_fortune", 0.5, "breaking_power", 5.0)),
	COMBAT("Combat", "COMBAT", "COMBAT",
			List.of(Slot.armor(), new Slot("Weapon", Set.of("SWORD", "BOW", "LONGSWORD"))),
			Map.of("health", 0.5, "defense", 0.5, "strength", 1.0, "critical_damage", 1.0, "true_defense", 1.0, "intelligence", 0.2),
			Map.of("damage", 1.0, "strength", 1.0, "critical_damage", 0.5, "ferocity", 1.0, "attack_speed", 0.5, "intelligence", 0.2)),
	FORAGING("Foraging", "FORAGING", "FORAGING",
			List.of(Slot.armor(), new Slot("Tool", Set.of("AXE"))),
			Map.of("foraging_fortune", 1.0, "sweep", 1.0),
			Map.of("foraging_fortune", 1.0, "sweep", 1.0)),
	FISHING("Fishing", "FISHING", "FISHING",
			List.of(Slot.armor(), new Slot("Rod", Set.of("FISHING_ROD"))),
			Map.of("sea_creature_chance", 10.0, "trophy_fish_chance", 1.0, "health", 0.05, "defense", 0.05),
			Map.of("sea_creature_chance", 10.0, "fishing_speed", 0.5, "trophy_fish_chance", 1.0, "damage", 0.1)),
	HUNTING("Hunting", "HUNTING", "HUNTING",
			List.of(new Slot("Huntaxe", Set.of("AXE")), new Slot("Lasso", Set.of("LASSO")), new Slot("Net", Set.of("FISHING_NET"))),
			Map.of(),
			Map.of("damage", 1.0)),
	DUNGEONS("Dungeons", "DUNGEONEERING", "CATACOMBS",
			List.of(Slot.armor(), new Slot("Weapon", Set.of("SWORD", "BOW", "LONGSWORD"))),
			Map.of("health", 0.5, "defense", 0.5, "strength", 1.0, "critical_damage", 1.0, "intelligence", 0.3),
			Map.of("damage", 1.0, "strength", 1.0, "critical_damage", 0.5, "ferocity", 1.0, "intelligence", 0.3));


	public final String title;
	public final String area;
	/** Profile key for the level shown next to the tab (a skill name, or CATACOMBS). */
	public final String levelKey;
	public final List<Slot> slots;
	public final Map<String, Double> armorWeights;
	public final Map<String, Double> toolWeights;

	SkillCategory(String title, String area, String levelKey, List<Slot> slots, Map<String, Double> armorWeights, Map<String, Double> toolWeights) {
		this.title = title;
		this.area = area;
		this.levelKey = levelKey;
		this.slots = slots;
		this.armorWeights = armorWeights;
		this.toolWeights = toolWeights;
	}

	public int level(PlayerProfile profile) {
		return levelKey.equals("CATACOMBS") ? profile.catacombsLevel : profile.skill(levelKey);
	}

	public record Slot(String label, Set<String> categories) {
		// Lives here, not on the enum: enum constants are built before the enum's own static fields.
		public static final Set<String> ARMOR = Set.of("HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS");

		static Slot armor() {
			return new Slot("Armor", ARMOR);
		}

		public boolean isArmor() {
			return categories == ARMOR;
		}
	}
}
