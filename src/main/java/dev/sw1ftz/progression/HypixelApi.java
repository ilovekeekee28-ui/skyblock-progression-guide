package dev.sw1ftz.progression;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads your selected SkyBlock profile from Hypixel's API using your own API key, and turns the raw
 * XP numbers into levels. Some parts (collections, accessory bag) only appear if you have those API
 * settings turned on in SkyBlock's settings menu.
 */
public class HypixelApi {
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private static final Pattern GENERATOR = Pattern.compile("^(.+)_(\\d+)$");
	private static final long MIN_INTERVAL_MS = 60_000;

	private volatile String status = "";
	private volatile boolean syncing;

	public String status() {
		return status;
	}

	public boolean syncing() {
		return syncing;
	}

	/** Syncs if you have a key and the last sync is older than {@code maxAgeMs}. */
	public void syncIfOld(PlayerProfile profile, long maxAgeMs) {
		if (!profile.apiKey.isBlank() && System.currentTimeMillis() - profile.lastApiSync > maxAgeMs) {
			sync(profile);
		}
	}

	public void sync(PlayerProfile profile) {
		Minecraft mc = Minecraft.getInstance();
		if (syncing || profile.apiKey.isBlank() || mc.player == null) {
			if (profile.apiKey.isBlank()) {
				status = "No API key set";
			}
			return;
		}
		if (System.currentTimeMillis() - profile.lastApiSync < MIN_INTERVAL_MS) {
			status = "Synced less than a minute ago";
			return;
		}
		String uuid = mc.player.getUUID().toString().replace("-", "");
		String key = profile.apiKey.trim();
		syncing = true;
		String username = mc.player.getName().getString();
		boolean skyCrypt = SkyCryptApi.isSkyCryptKey(key);
		status = skyCrypt ? "Syncing with SkyCrypt..." : "Syncing with Hypixel...";
		CompletableFuture.runAsync(() -> {
			try {
				if (skyCrypt) {
					String name = SkyCryptApi.sync(profile, username, key);
					profile.lastApiSync = System.currentTimeMillis();
					ProgressionClient.get().profileChanged();
					status = "Synced from SkyCrypt (" + name + ")";
					return;
				}
				JsonObject response = get("https://api.hypixel.net/v2/skyblock/profiles?uuid=" + uuid, key);
				JsonObject selected = null;
				for (JsonElement p : response.getAsJsonArray("profiles")) {
					JsonObject o = p.getAsJsonObject();
					if (selected == null || (o.has("selected") && o.get("selected").getAsBoolean())) {
						selected = o;
					}
				}
				if (selected == null) {
					status = "No SkyBlock profiles found";
					return;
				}
				JsonObject member = selected.getAsJsonObject("members").getAsJsonObject(uuid);
				JsonObject garden = null;
				try {
					garden = get("https://api.hypixel.net/v2/skyblock/garden?profile=" + selected.get("profile_id").getAsString(), key)
							.getAsJsonObject("garden");
				} catch (Exception e) {
					ProgressionClient.LOGGER.info("No garden data: {}", e.getMessage());
				}
				apply(profile, member, garden);
				try {
					JsonObject museum = obj(obj(get("https://api.hypixel.net/v2/skyblock/museum?profile=" + selected.get("profile_id").getAsString(), key),
							"members"), uuid);
					JsonObject donated = obj(museum, "items");
					if (!donated.isEmpty()) {
						Map<String, String> mapped = ProgressionClient.get().repo().museumMapped();
						Set<String> ids = new LinkedHashSet<>();
						donated.keySet().forEach(id -> ids.add(mapped.getOrDefault(id, id)));
						profile.museum = ids;
						profile.known.add("MUSEUM");
					}
				} catch (Exception e) {
					ProgressionClient.LOGGER.info("No museum data: {}", e.getMessage());
				}
				profile.lastApiSync = System.currentTimeMillis();
				ProgressionClient.get().profileChanged();
				status = "Synced from Hypixel (" + selected.get("cute_name").getAsString() + ")";
			} catch (ApiException e) {
				status = e.getMessage();
			} catch (Exception e) {
				ProgressionClient.LOGGER.warn("API sync failed", e);
				status = "Sync failed: " + e.getClass().getSimpleName();
			} finally {
				syncing = false;
			}
		});
	}

	private static JsonObject get(String url, String key) throws Exception {
		HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
				.header("API-Key", key).header("User-Agent", "SkyBlockProgression-Mod/1.0").build(), HttpResponse.BodyHandlers.ofString());
		switch (response.statusCode()) {
			case 200 -> {
				return JsonParser.parseString(response.body()).getAsJsonObject();
			}
			case 403 -> throw new ApiException("Invalid or expired API key");
			case 429 -> throw new ApiException("Hypixel rate limit, try again in a minute");
			default -> throw new ApiException("Hypixel API error " + response.statusCode());
		}
	}

	static final class ApiException extends Exception {
		ApiException(String message) {
			super(message);
		}
	}

	/** Copies everything we can from the API response into your profile. */
	static void apply(PlayerProfile profile, JsonObject member, JsonObject garden) {
		RepoData repo = ProgressionClient.get().repo();

		JsonObject playerData = obj(member, "player_data");
		JsonObject experience = obj(playerData, "experience");
		int farmingCapBonus = (int) num(obj(obj(member, "jacobs_contest"), "perks"), "farming_level_cap");
		for (Map.Entry<String, JsonElement> e : experience.entrySet()) {
			String skill = e.getKey().replace("SKILL_", "");
			profile.skills.put(skill, repo.skillLevel(skill, e.getValue().getAsDouble(), skill.equals("FARMING") ? farmingCapBonus : 0));
		}

		JsonObject catacombs = obj(obj(obj(member, "dungeons"), "dungeon_types"), "catacombs");
		if (catacombs.has("experience")) {
			profile.catacombsLevel = repo.catacombsLevel(catacombs.get("experience").getAsDouble());
			int floor = 0;
			for (Map.Entry<String, JsonElement> e : obj(catacombs, "tier_completions").entrySet()) {
				try {
					if (e.getValue().getAsDouble() > 0) {
						floor = Math.max(floor, Integer.parseInt(e.getKey()));
					}
				} catch (NumberFormatException ignored) {
					// "total" and similar keys
				}
			}
			profile.catacombsFloor = floor;
		}

		JsonObject bosses = obj(obj(member, "slayer"), "slayer_bosses");
		if (!bosses.isEmpty()) {
			for (Map.Entry<String, JsonElement> e : bosses.entrySet()) {
				double xp = num(e.getValue().getAsJsonObject(), "xp");
				profile.slayerXp.put(e.getKey(), xp);
				profile.slayers.put(e.getKey(), repo.slayerLevel(e.getKey(), xp));
			}
			profile.known.add("SLAYER");
		}

		JsonObject nether = obj(member, "nether_island_player_data");
		if (!nether.isEmpty()) {
			Set<String> kuudra = new LinkedHashSet<>();
			for (Map.Entry<String, JsonElement> e : obj(nether, "kuudra_completed_tiers").entrySet()) {
				String tier = e.getKey().toUpperCase(Locale.ROOT);
				if (Requirement.KUUDRA_TIERS.contains(tier) && e.getValue().getAsDouble() > 0) {
					kuudra.add(tier);
				}
			}
			profile.kuudraTiers = kuudra;
			profile.known.add("KUUDRA");
			profile.reputation.put("BARBARIANS", (int) num(nether, "barbarians_reputation"));
			profile.reputation.put("MAGES", (int) num(nether, "mages_reputation"));
			profile.known.add("REPUTATION");
		}

		JsonObject trophy = obj(member, "trophy_fish");
		if (trophy.has("rewards")) {
			Set<String> rewards = new LinkedHashSet<>();
			for (JsonElement r : trophy.getAsJsonArray("rewards")) {
				int index = r.getAsInt() - 1;
				if (index >= 0 && index < Requirement.TROPHY_REWARDS.size()) {
					rewards.add(Requirement.TROPHY_REWARDS.get(index));
				}
			}
			profile.trophyRewards = rewards;
			profile.known.add("TROPHY");
		}

		if (member.has("collection")) {
			Map<String, Integer> tiers = new LinkedHashMap<>();
			for (Map.Entry<String, JsonElement> e : member.getAsJsonObject("collection").entrySet()) {
				tiers.put(e.getKey(), repo.collectionTier(e.getKey(), e.getValue().getAsLong()));
			}
			profile.collections = tiers;
			profile.known.add("COLLECTION");
		}

		// HOTM moved between fields over time; accept either.
		double hotmXp = num(obj(member, "mining_core"), "experience");
		if (hotmXp == 0) {
			hotmXp = num(obj(obj(member, "skill_tree"), "experience"), "mining");
		}
		if (hotmXp > 0) {
			profile.hotmTier = repo.hotmTier(hotmXp);
		}

		if (garden != null && garden.has("garden_experience")) {
			profile.gardenLevel = repo.gardenLevel(garden.get("garden_experience").getAsDouble());
		}

		if (playerData.has("crafted_generators")) {
			Map<String, Integer> crafted = new LinkedHashMap<>();
			for (JsonElement g : playerData.getAsJsonArray("crafted_generators")) {
				Matcher m = GENERATOR.matcher(g.getAsString());
				if (m.matches()) {
					crafted.merge(m.group(1), Integer.parseInt(m.group(2)), Math::max);
				}
			}
			profile.craftedMinions = crafted;
		}

		profile.purse = num(obj(member, "currencies"), "coin_purse");
		if (obj(member, "garden_player_data").has("copper")) {
			profile.copper = (long) num(obj(member, "garden_player_data"), "copper");
		}

		JsonObject inventory = obj(member, "inventory");
		for (String part : new String[] {"inv_armor", "inv_contents", "ender_chest_contents", "wardrobe_contents", "equipment_contents"}) {
			JsonObject blob = obj(inventory, part);
			if (blob.has("data")) {
				profile.ownedItems.addAll(itemIds(blob.get("data").getAsString()));
			}
		}

		JsonObject bag = obj(obj(inventory, "bag_contents"), "talisman_bag");
		if (bag.has("data")) {
			Set<String> ids = itemIds(bag.get("data").getAsString());
			if (!ids.isEmpty()) {
				profile.accessories = ids;
				profile.known.add("ACCESSORIES");
			}
		}
	}

	/** Item ids inside one of Hypixel's base64 + gzip NBT inventory blobs. */
	static Set<String> itemIds(String base64) {
		Set<String> ids = new LinkedHashSet<>();
		try {
			CompoundTag root = NbtIo.readCompressed(new ByteArrayInputStream(Base64.getDecoder().decode(base64)), NbtAccounter.unlimitedHeap());
			ListTag list = root.getListOrEmpty("i");
			for (int i = 0; i < list.size(); i++) {
				String id = list.getCompoundOrEmpty(i).getCompoundOrEmpty("tag").getCompoundOrEmpty("ExtraAttributes").getStringOr("id", "");
				if (!id.isEmpty()) {
					ids.add(id);
				}
			}
		} catch (Exception e) {
			ProgressionClient.LOGGER.warn("Could not read inventory data", e);
		}
		return ids;
	}

	private static JsonObject obj(JsonObject parent, String key) {
		return parent != null && parent.has(key) && parent.get(key).isJsonObject() ? parent.getAsJsonObject(key) : new JsonObject();
	}

	private static double num(JsonObject parent, String key) {
		return parent != null && parent.has(key) && parent.get(key).isJsonPrimitive() ? parent.get(key).getAsDouble() : 0;
	}
}
