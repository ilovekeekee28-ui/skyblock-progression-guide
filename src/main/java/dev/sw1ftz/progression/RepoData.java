package dev.sw1ftz.progression;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reference data that Hypixel's API doesn't provide, from the community NotEnoughUpdates repo (MIT):
 * XP tables, island names, accessory upgrade families and minion stats. Plus Hypixel's public
 * collections list for collection tiers. Everything is cached on disk for a day.
 */
public class RepoData {
	private static final String NEU = "https://raw.githubusercontent.com/NotEnoughUpdates/NotEnoughUpdates-REPO/master/";
	private static final String COLLECTIONS = "https://api.hypixel.net/v2/resources/skyblock/collections";
	private static final Duration MAX_AGE = Duration.ofHours(24);
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private static final Pattern ACTION_TIME = Pattern.compile("Time Between Actions: ([\\d.]+)s");
	private static final Pattern MAX_STORAGE = Pattern.compile("Max Storage: ([\\d,]+)");

	/** Per-level XP for skills (index 0 = XP from level 0 to 1). */
	private volatile long[] skillXp = new long[0];
	private volatile long[] catacombsXp = new long[0];
	private volatile long[] hotmXp = new long[0];
	private volatile long[] gardenXp = new long[0];
	private volatile Map<String, Integer> skillCaps = Map.of();
	/** Cumulative XP needed for each slayer level, per boss type (zombie, spider, ...). */
	private volatile Map<String, long[]> slayerXp = Map.of();
	private volatile Map<String, String> areaNames = Map.of();
	/** Accessory id to the ids that replace it (e.g. a talisman to its ring and artifact). */
	private volatile Map<String, List<String>> accessoryUpgrades = Map.of();
	private volatile Set<String> ignoredAccessories = Set.of();
	private volatile Map<String, Integer> minionMaxTier = Map.of();
	/** Collection item id to the amounts needed for tier 1, 2, ... */
	private volatile Map<String, long[]> collectionTiers = Map.of();
	/** Collection display name (lower case) to its item id. */
	private volatile Map<String, String> collectionIdsByName = Map.of();
	private volatile List<MuseumEntry> museumEntries = List.of();
	/** Museum item to the lower item that donating it also counts for. */
	private volatile Map<String, String> museumChildren = Map.of();
	/** Item id variants (e.g. starred) to the id the museum knows them by. */
	private volatile Map<String, String> museumMapped = Map.of();
	private final Map<String, MinionTier> minionTiers = new ConcurrentHashMap<>();
	private volatile boolean loaded;

	/** One minion tier: speed, storage and what it costs to craft. */
	public record MinionTier(String id, double secondsPerAction, int storage, Map<String, Integer> recipe) {
	}

	/** One thing the museum takes: a single item, or an armor set (more than one piece). */
	public record MuseumEntry(String id, String category, int xp, List<String> pieces, boolean set) {
	}

	public List<MuseumEntry> museumEntries() {
		return museumEntries;
	}

	public Map<String, String> museumChildren() {
		return museumChildren;
	}

	public Map<String, String> museumMapped() {
		return museumMapped;
	}

	private void loadMuseum() throws Exception {
		JsonObject museum = json("museum.json", NEU + "constants/museum.json").getAsJsonObject();
		JsonObject xp = museum.getAsJsonObject("itemToXp");
		JsonObject sets = museum.getAsJsonObject("sets_to_items");
		List<MuseumEntry> entries = new ArrayList<>();
		for (Map.Entry<String, JsonElement> category : museum.getAsJsonObject("items").entrySet()) {
			if (category.getKey().equals("special")) {
				continue; // one-off event items, not part of museum progress
			}
			for (JsonElement item : category.getValue().getAsJsonArray()) {
				String id = item.getAsString();
				List<String> pieces = new ArrayList<>();
				if (sets.has(id)) {
					sets.getAsJsonArray(id).forEach(p -> pieces.add(p.getAsString()));
				}
				boolean set = !pieces.isEmpty();
				entries.add(new MuseumEntry(id, category.getKey(), xp.has(id) ? xp.get(id).getAsInt() : 0, set ? List.copyOf(pieces) : List.of(id), set));
			}
		}
		Map<String, String> children = new HashMap<>();
		museum.getAsJsonObject("children").entrySet().forEach(e -> children.put(e.getKey(), e.getValue().getAsString()));
		Map<String, String> mapped = new HashMap<>();
		museum.getAsJsonObject("mapped_ids").entrySet().forEach(e -> mapped.put(e.getKey(), e.getValue().getAsString()));
		museumChildren = Map.copyOf(children);
		museumMapped = Map.copyOf(mapped);
		museumEntries = List.copyOf(entries);
	}

	public boolean loaded() {
		return loaded;
	}

	public void load() {
		CompletableFuture.runAsync(() -> {
			try {
				loadMuseum();
			} catch (Exception e) {
				ProgressionClient.LOGGER.warn("Could not load museum data", e);
			}
		});
		CompletableFuture.runAsync(() -> {
			try {
				JsonObject leveling = json("leveling.json", NEU + "constants/leveling.json").getAsJsonObject();
				skillXp = longs(leveling.getAsJsonArray("leveling_xp"));
				catacombsXp = longs(leveling.getAsJsonArray("catacombs"));
				hotmXp = longs(leveling.getAsJsonArray("HOTM"));
				Map<String, Integer> caps = new HashMap<>();
				for (Map.Entry<String, JsonElement> e : leveling.getAsJsonObject("leveling_caps").entrySet()) {
					caps.put(e.getKey().toUpperCase(Locale.ROOT), e.getValue().getAsInt());
				}
				skillCaps = Map.copyOf(caps);
				Map<String, long[]> slayers = new HashMap<>();
				for (Map.Entry<String, JsonElement> e : leveling.getAsJsonObject("slayer_xp").entrySet()) {
					slayers.put(e.getKey(), longs(e.getValue().getAsJsonArray()));
				}
				slayerXp = Map.copyOf(slayers);

				JsonObject misc = json("misc.json", NEU + "constants/misc.json").getAsJsonObject();
				Map<String, String> areas = new HashMap<>();
				for (Map.Entry<String, JsonElement> e : misc.getAsJsonObject("area_names").entrySet()) {
					areas.put(e.getKey(), e.getValue().getAsString());
				}
				areaNames = Map.copyOf(areas);
				Map<String, List<String>> upgrades = new HashMap<>();
				for (Map.Entry<String, JsonElement> e : misc.getAsJsonObject("talisman_upgrades").entrySet()) {
					List<String> ids = new ArrayList<>();
					e.getValue().getAsJsonArray().forEach(id -> ids.add(id.getAsString()));
					upgrades.put(e.getKey(), List.copyOf(ids));
				}
				accessoryUpgrades = Map.copyOf(upgrades);
				Set<String> ignored = new HashSet<>();
				misc.getAsJsonArray("ignored_talisman").forEach(id -> ignored.add(id.getAsString()));
				ignoredAccessories = Set.copyOf(ignored);
				Map<String, Integer> maxTiers = new HashMap<>();
				for (Map.Entry<String, JsonElement> e : misc.getAsJsonObject("minions").entrySet()) {
					maxTiers.put(e.getKey(), e.getValue().getAsInt());
				}
				minionMaxTier = Map.copyOf(maxTiers);

				JsonObject garden = json("garden.json", NEU + "constants/garden.json").getAsJsonObject();
				gardenXp = longs(garden.getAsJsonArray("garden_exp"));

				JsonObject collections = json("collections.json", COLLECTIONS).getAsJsonObject().getAsJsonObject("collections");
				Map<String, long[]> tiers = new HashMap<>();
				Map<String, String> byName = new HashMap<>();
				for (Map.Entry<String, JsonElement> category : collections.entrySet()) {
					for (Map.Entry<String, JsonElement> item : category.getValue().getAsJsonObject().getAsJsonObject("items").entrySet()) {
						JsonObject o = item.getValue().getAsJsonObject();
						JsonArray list = o.getAsJsonArray("tiers");
						long[] amounts = new long[list.size()];
						for (int i = 0; i < list.size(); i++) {
							amounts[i] = list.get(i).getAsJsonObject().get("amountRequired").getAsLong();
						}
						tiers.put(item.getKey(), amounts);
						byName.put(o.get("name").getAsString().toLowerCase(Locale.ROOT), item.getKey());
					}
				}
				collectionTiers = Map.copyOf(tiers);
				collectionIdsByName = Map.copyOf(byName);
				loaded = true;
			} catch (Exception e) {
				ProgressionClient.LOGGER.warn("Could not load reference data", e);
			}
		});
	}

	// ---------------------------------------------------------------- levels from XP

	/** Level for a per-level XP table, e.g. skills: 0 XP = level 0. */
	private static int levelFor(long[] perLevel, double xp, int cap) {
		int level = 0;
		double remaining = xp;
		for (long need : perLevel) {
			if (level >= cap || remaining < need) {
				break;
			}
			remaining -= need;
			level++;
		}
		return level;
	}

	public int skillLevel(String skill, double xp, int extraCap) {
		return levelFor(skillXp, xp, skillCaps.getOrDefault(skill, 50) + extraCap);
	}

	public int catacombsLevel(double xp) {
		return levelFor(catacombsXp, xp, 50);
	}

	/** HOTM's table starts with the 0 XP needed for tier 1. */
	public int hotmTier(double xp) {
		return levelFor(hotmXp, xp, hotmXp.length);
	}

	public int gardenLevel(double xp) {
		return levelFor(gardenXp, xp, gardenXp.length);
	}

	public int slayerLevel(String type, double xp) {
		long[] needed = slayerXp.get(type);
		if (needed == null) {
			return 0;
		}
		int level = 0;
		for (long n : needed) {
			if (xp >= n) {
				level++;
			}
		}
		return level;
	}

	/** XP still needed for the next slayer level, or -1 when maxed. */
	public long slayerXpToNext(String type, double xp) {
		long[] needed = slayerXp.get(type);
		if (needed == null) {
			return -1;
		}
		for (long n : needed) {
			if (xp < n) {
				return (long) Math.ceil(n - xp);
			}
		}
		return -1;
	}

	public int collectionTier(String id, long amount) {
		long[] amounts = collectionTiers.get(id);
		if (amounts == null) {
			return 0;
		}
		int tier = 0;
		for (long a : amounts) {
			if (amount >= a) {
				tier++;
			}
		}
		return tier;
	}

	public String collectionIdForName(String name) {
		return collectionIdsByName.get(name.toLowerCase(Locale.ROOT));
	}

	public String areaName(String key) {
		return areaNames.get(key);
	}

	public Map<String, String> areaNames() {
		return areaNames;
	}

	public Map<String, List<String>> accessoryUpgrades() {
		return accessoryUpgrades;
	}

	public Set<String> ignoredAccessories() {
		return ignoredAccessories;
	}

	public Map<String, Integer> minionMaxTier() {
		return minionMaxTier;
	}

	// ---------------------------------------------------------------- minions

	/** Minion tier stats, fetched from the repo the first time they're needed. Null until loaded. */
	public MinionTier minionTier(String generatorId) {
		MinionTier cached = minionTiers.get(generatorId);
		if (cached == null && !minionTiers.containsKey(generatorId + "#loading")) {
			minionTiers.put(generatorId + "#loading", new MinionTier(generatorId, 0, 0, Map.of()));
			CompletableFuture.runAsync(() -> {
				try {
					JsonObject o = json("minions/" + generatorId + ".json", NEU + "items/" + generatorId + ".json").getAsJsonObject();
					double seconds = 0;
					int storage = 0;
					for (JsonElement line : o.getAsJsonArray("lore")) {
						String text = line.getAsString().replaceAll("§.", "");
						Matcher a = ACTION_TIME.matcher(text);
						if (a.find()) {
							seconds = Double.parseDouble(a.group(1));
						}
						Matcher s = MAX_STORAGE.matcher(text);
						if (s.find()) {
							storage = Integer.parseInt(s.group(1).replace(",", ""));
						}
					}
					Map<String, Integer> recipe = new HashMap<>();
					if (o.has("recipe")) {
						for (Map.Entry<String, JsonElement> slot : o.getAsJsonObject("recipe").entrySet()) {
							String value = slot.getValue().getAsString();
							if (value.isEmpty()) {
								continue;
							}
							String[] parts = value.split(":");
							recipe.merge(parts[0], parts.length > 1 ? Integer.parseInt(parts[1]) : 1, Integer::sum);
						}
					}
					minionTiers.put(generatorId, new MinionTier(generatorId, seconds, storage, Map.copyOf(recipe)));
				} catch (Exception e) {
					ProgressionClient.LOGGER.warn("Could not load minion {}", generatorId, e);
				}
			});
		}
		return cached;
	}

	// ---------------------------------------------------------------- recipes

	private final Map<String, Map<String, Double>> recipes = new ConcurrentHashMap<>();
	private final Set<String> recipesLoading = ConcurrentHashMap.newKeySet();

	/**
	 * What it takes to make an item: ingredient id to amount, from its crafting, forge or NPC trade recipe
	 * ("SKYBLOCK_COIN" is coins). Empty when it has none; null until fetched (the first call starts it).
	 */
	public Map<String, Double> recipe(String id) {
		Map<String, Double> cached = recipes.get(id);
		if (cached == null && recipesLoading.add(id)) {
			CompletableFuture.runAsync(() -> {
				Map<String, Double> out = new HashMap<>();
				try {
					JsonObject o = json("items/" + id + ".json", NEU + "items/" + id + ".json").getAsJsonObject();
					if (o.has("recipe")) {
						addGrid(o.getAsJsonObject("recipe"), out);
					} else if (o.has("recipes")) {
						for (JsonElement r : o.getAsJsonArray("recipes")) {
							JsonObject recipe = r.getAsJsonObject();
							String type = recipe.has("type") ? recipe.get("type").getAsString() : "crafting";
							if (type.equals("forge") && recipe.has("inputs")) {
								recipe.getAsJsonArray("inputs").forEach(e -> addIngredient(e.getAsString(), out));
								if (recipe.has("coins")) {
									out.merge("SKYBLOCK_COIN", recipe.get("coins").getAsDouble(), Double::sum);
								}
							} else if (type.equals("npc_shop") && recipe.has("cost")) {
								recipe.getAsJsonArray("cost").forEach(e -> addIngredient(e.getAsString(), out));
							} else if (type.equals("crafting")) {
								addGrid(recipe, out);
							}
							if (!out.isEmpty()) {
								break;
							}
						}
					}
				} catch (Exception e) {
					ProgressionClient.LOGGER.debug("No recipe for {}", id, e);
				}
				out.remove(id);
				recipes.put(id, Map.copyOf(out));
			});
		}
		return cached;
	}

	private static void addGrid(JsonObject grid, Map<String, Double> out) {
		for (String slot : List.of("A1", "A2", "A3", "B1", "B2", "B3", "C1", "C2", "C3")) {
			if (grid.has(slot)) {
				addIngredient(grid.get(slot).getAsString(), out);
			}
		}
	}

	private static void addIngredient(String value, Map<String, Double> out) {
		if (value == null || value.isBlank()) {
			return;
		}
		String[] parts = value.split(":");
		double amount = 1;
		if (parts.length > 1) {
			try {
				amount = Double.parseDouble(parts[1]);
			} catch (NumberFormatException ignored) {
			}
		}
		out.merge(parts[0], amount, Double::sum);
	}

	// ---------------------------------------------------------------- download + cache

	private static JsonElement json(String cacheName, String url) throws Exception {
		Path cache = FabricLoader.getInstance().getConfigDir().resolve("skyblockprogression").resolve("repo").resolve(cacheName);
		boolean fresh = Files.exists(cache) && Files.getLastModifiedTime(cache).toInstant().isAfter(Instant.now().minus(MAX_AGE));
		if (!fresh) {
			try {
				HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
						.header("User-Agent", "SkyBlockProgression-Mod/1.0").build(), HttpResponse.BodyHandlers.ofString());
				if (response.statusCode() == 200) {
					Files.createDirectories(cache.getParent());
					Files.writeString(cache, response.body());
					return JsonParser.parseString(response.body());
				}
			} catch (Exception e) {
				if (!Files.exists(cache)) {
					throw e;
				}
			}
		}
		return JsonParser.parseString(Files.readString(cache));
	}

	private static long[] longs(JsonArray array) {
		long[] out = new long[array.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = array.get(i).getAsLong();
		}
		return out;
	}
}
