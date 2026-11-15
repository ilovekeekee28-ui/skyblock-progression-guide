package dev.sw1ftz.progression;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything the guide knows about you, saved in config/skyblockprogression/profile.json so it's
 * remembered next time. Filled from your Hypixel API key (if set) and from menus you open.
 */
public class PlayerProfile {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("skyblockprogression").resolve("profile.json");
	}

	/** Skill name (FARMING, MINING, ...) to level. */
	public Map<String, Integer> skills = new LinkedHashMap<>();
	public int hotmTier = 0;
	public int gardenLevel = 0;
	public int catacombsLevel = 0;
	/** Highest Catacombs floor completed, 0 = none. */
	public int catacombsFloor = 0;
	/** Slayer boss type (zombie, spider, wolf, enderman, blaze, vampire) to level, and the XP behind it. */
	public Map<String, Integer> slayers = new LinkedHashMap<>();
	public Map<String, Double> slayerXp = new LinkedHashMap<>();
	/** Collection item id (e.g. HARD_STONE) to unlocked tier. */
	public Map<String, Integer> collections = new LinkedHashMap<>();
	/** Kuudra tiers completed: NONE (basic), HOT, BURNING, FIERY, INFERNAL. */
	public Set<String> kuudraTiers = new LinkedHashSet<>();
	/** Trophy Hunter rewards earned: BRONZE, SILVER, GOLD, DIAMOND. */
	public Set<String> trophyRewards = new LinkedHashSet<>();
	/** Crimson Isle faction (BARBARIANS, MAGES) to reputation. */
	public Map<String, Integer> reputation = new LinkedHashMap<>();
	/** Accessory ids in your accessory bag. */
	public Set<String> accessories = new LinkedHashSet<>();
	/** Item ids you own (inventory, armor, wardrobe, ender chest), so upgrades skip what you already have. */
	public Set<String> ownedItems = new LinkedHashSet<>();
	/** Minion type (e.g. COBBLESTONE) to the highest tier you've crafted. */
	public Map<String, Integer> craftedMinions = new LinkedHashMap<>();
	/** Minions you've opened, with what was in them at the time. */
	public List<MinionState> minions = new ArrayList<>();
	/** Which kinds of data we have, e.g. "SLAYER", "COLLECTION": requirements of other kinds can't be checked. */
	public Set<String> known = new LinkedHashSet<>();
	public double purse = 0;
	/** Garden copper, or -1 until we've seen it. */
	public long copper = -1;

	/** Your Hypixel API key, stored only in this file on your computer. */
	public String apiKey = "";
	public long lastApiSync = 0;

	/** Last tab shown in the guide. */
	public String lastCategory = "FARMING";
	/** Fairy souls you have collected, as "island:x,y,z". */
	public Set<String> foundSouls = new LinkedHashSet<>();
	/** Draw boxes and beams on unfound fairy souls in the world. */
	public boolean soulOutlines = true;
	/** Chat alerts when a minion is estimated to be full. */
	public boolean minionAlerts = true;

	/** What you're working towards, shown on the floating HUD: "SKILL:FARMING", "ITEM:<id>" or "SLAYER:zombie". */
	public List<String> pins = new ArrayList<>();
	public boolean showHud = true;
	/** Upgrades tab filter: "BEST" (cheaper of buying or crafting), "CRAFT" or "BUY". */
	public String upgradeWay = "BEST";
	/** Upgrades tab: hide what your purse can't pay for. */
	public boolean onlyAffordable = false;
	/** Things you've donated to the museum, by museum id. */
	public Set<String> museum = new LinkedHashSet<>();
	/** Museum tab: show only armor sets, or single items too. */
	public boolean museumSetsOnly = true;
	/** Accessories tab: sort by most Magical Power instead of cheapest per MP. */
	public boolean accessoriesByMp = false;
	/** One-key inventory sort; off unless you turn it on with /sbprog autosort. */
	public boolean autoSort = false;
	public int hudX = 6;
	public int hudY = 60;

	/** Pins it, or unpins it if it was already pinned. Returns true when it is now pinned. */
	public boolean togglePin(String pin) {
		boolean pinned = !pins.remove(pin);
		if (pinned) {
			pins.add(pin);
		}
		save();
		return pinned;
	}

	/** A minion as last seen in its menu. Stored/capacity are item counts. */
	public static class MinionState {
		public String type;
		public int tier;
		public int x, y, z;
		public long seenAt;
		public int stored;
		public int capacity;
		public boolean alerted;
	}

	public int skill(String name) {
		return skills.getOrDefault(name, 0);
	}

	/** Whether we have the data to check this requirement. */
	public boolean tracks(Requirement r) {
		return switch (r.kind()) {
			case SKILL, HOTM, GARDEN, CATACOMBS_LEVEL, CATACOMBS_FLOOR -> true;
			case SLAYER -> known.contains("SLAYER");
			case COLLECTION -> known.contains("COLLECTION") || collections.containsKey(r.key());
			case KUUDRA -> known.contains("KUUDRA");
			case TROPHY -> known.contains("TROPHY");
			case REPUTATION -> known.contains("REPUTATION");
			case ANY_OF -> r.anyOf().isEmpty() || r.anyOf().stream().anyMatch(this::tracks);
			case OTHER -> false;
		};
	}

	/**
	 * True when the requirement is met. Things we have no data for count as not done, so the guide
	 * never recommends gear you might not be able to use.
	 */
	public boolean meets(Requirement r) {
		return switch (r.kind()) {
			case SKILL -> r.key().equals("CATACOMBS") ? catacombsLevel >= r.level() : skill(r.key()) >= r.level();
			case HOTM -> hotmTier >= r.level();
			case GARDEN -> gardenLevel >= r.level();
			case CATACOMBS_LEVEL -> catacombsLevel >= r.level();
			case CATACOMBS_FLOOR -> catacombsFloor >= r.level();
			case SLAYER -> slayers.getOrDefault(r.key(), 0) >= r.level();
			case COLLECTION -> collections.getOrDefault(r.key(), 0) >= r.level();
			case KUUDRA -> kuudraTiers.stream().anyMatch(t -> Requirement.KUUDRA_TIERS.indexOf(t) >= r.level());
			case TROPHY -> trophyRewards.stream().anyMatch(t -> Requirement.TROPHY_REWARDS.indexOf(t) >= r.level());
			case REPUTATION -> reputation.getOrDefault(r.key(), 0) >= r.level();
			case ANY_OF -> r.anyOf().isEmpty() || r.anyOf().stream().anyMatch(this::meets);
			case OTHER -> false;
		};
	}

	public static PlayerProfile load() {
		try {
			if (Files.exists(path())) {
				PlayerProfile profile = GSON.fromJson(Files.readString(path()), PlayerProfile.class);
				if (profile != null) {
					profile.fillMissing();
					return profile;
				}
			}
		} catch (Exception e) {
			ProgressionClient.LOGGER.warn("Could not read {}, starting fresh", path(), e);
		}
		return new PlayerProfile();
	}

	/** Older save files don't have the newer fields. */
	private void fillMissing() {
		PlayerProfile defaults = new PlayerProfile();
		if (skills == null) skills = defaults.skills;
		if (slayers == null) slayers = defaults.slayers;
		if (slayerXp == null) slayerXp = defaults.slayerXp;
		if (collections == null) collections = defaults.collections;
		if (kuudraTiers == null) kuudraTiers = defaults.kuudraTiers;
		if (trophyRewards == null) trophyRewards = defaults.trophyRewards;
		if (reputation == null) reputation = defaults.reputation;
		if (accessories == null) accessories = defaults.accessories;
		if (ownedItems == null) ownedItems = defaults.ownedItems;
		if (craftedMinions == null) craftedMinions = defaults.craftedMinions;
		if (minions == null) minions = defaults.minions;
		if (known == null) known = defaults.known;
		if (foundSouls == null) foundSouls = defaults.foundSouls;
		if (apiKey == null) apiKey = "";
		if (museum == null) museum = defaults.museum;
		if (pins == null) pins = defaults.pins;
		if (upgradeWay == null) upgradeWay = defaults.upgradeWay;
		if (lastCategory == null) lastCategory = "FARMING";
	}

	public synchronized void save() {
		try {
			Files.createDirectories(path().getParent());
			Files.writeString(path(), GSON.toJson(this));
		} catch (IOException e) {
			ProgressionClient.LOGGER.warn("Could not save {}", path(), e);
		}
	}
}
