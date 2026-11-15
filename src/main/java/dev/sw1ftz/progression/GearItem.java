package dev.sw1ftz.progression;

import java.util.List;
import java.util.Map;

/**
 * One SkyBlock item from Hypixel's public item list, reduced to what the guide needs.
 *
 * @param category Hypixel's item category, e.g. CHESTPLATE, DRILL, FARMING_TOOL
 * @param area     the museum category, i.e. which part of the game it belongs to (FARMING, MINING, ...)
 * @param stage    Hypixel's progression stage, 0 = Starter ... 6 = Master, -1 when unknown
 * @param stats    lower-case stat name to value
 * @param setId    armor set this piece belongs to, or null
 */
public record GearItem(
		String id,
		String name,
		String category,
		String rarity,
		String area,
		int stage,
		Map<String, Double> stats,
		List<Requirement> requirements,
		String setId
) {
	public static final String[] STAGES = {"Starter", "Amateur", "Intermediate", "Skilled", "Expert", "Professional", "Master"};
	public static final String[] RARITIES = {"COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC", "DIVINE", "SPECIAL", "VERY_SPECIAL"};

	public static int stageIndex(String stage) {
		for (int i = 0; i < STAGES.length; i++) {
			if (STAGES[i].equalsIgnoreCase(stage)) {
				return i;
			}
		}
		return -1;
	}

	public static int rarityIndex(String rarity) {
		for (int i = 0; i < RARITIES.length; i++) {
			if (RARITIES[i].equals(rarity)) {
				return i;
			}
		}
		return 0;
	}

	public String stageName() {
		return stage >= 0 ? STAGES[stage] : "Unranked";
	}

	public double stat(String key) {
		return stats.getOrDefault(key, 0.0);
	}
}
