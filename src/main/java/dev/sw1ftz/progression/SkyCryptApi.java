package dev.sw1ftz.progression;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads your selected profile from SkyCrypt by username, using your own SkyCrypt API key. SkyCrypt sends
 * levels already worked out, so this only copies them across.
 */
final class SkyCryptApi {
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL).build();
	private static final String BASE = "https://api.shiiyu.moe/api/";
	private static final Pattern FLOOR = Pattern.compile("(\\d+)");

	private SkyCryptApi() {
	}

	/** Hypixel keys are UUIDs; anything else is taken as a SkyCrypt key. */
	static boolean isSkyCryptKey(String key) {
		return !key.trim().matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
	}

	/** Syncs the profile and returns its name, e.g. "Apple". */
	static String sync(PlayerProfile profile, String username, String key) throws Exception {
		JsonObject stats = get("stats/" + username, key);
		String profileId = stats.get("profile_id").getAsString();
		JsonObject combined = get("combined/" + stats.get("uuid").getAsString() + "/" + profileId, key);
		apply(profile, stats, combined);
		return stats.has("profile_cute_name") ? stats.get("profile_cute_name").getAsString() : profileId;
	}

	private static JsonObject get(String path, String key) throws Exception {
		HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(BASE + path)).timeout(Duration.ofSeconds(30))
				.header("x-api-key", key.trim()).header("User-Agent", "SkyBlockProgression-Mod/1.0").build(), HttpResponse.BodyHandlers.ofString());
		return switch (response.statusCode()) {
			case 200 -> JsonParser.parseString(response.body()).getAsJsonObject();
			case 401, 403 -> throw new HypixelApi.ApiException("Invalid SkyCrypt API key");
			case 404 -> throw new HypixelApi.ApiException("SkyCrypt couldn't find your profile");
			case 429 -> throw new HypixelApi.ApiException("SkyCrypt rate limit, try again later");
			default -> throw new HypixelApi.ApiException("SkyCrypt error " + response.statusCode());
		};
	}

	static void apply(PlayerProfile profile, JsonObject stats, JsonObject combined) {
		for (Map.Entry<String, JsonElement> e : obj(obj(stats, "skills"), "skills").entrySet()) {
			if (e.getValue().isJsonObject()) {
				profile.skills.put(e.getKey().toUpperCase(Locale.ROOT), (int) num(e.getValue().getAsJsonObject(), "level"));
			}
		}
		profile.purse = num(stats, "purse");

		JsonObject dungeons = obj(combined, "dungeons");
		if (dungeons.has("level")) {
			profile.catacombsLevel = (int) num(obj(dungeons, "level"), "level");
			int floor = 0;
			if (dungeons.has("catacombs") && dungeons.get("catacombs").isJsonArray()) {
				for (JsonElement f : dungeons.getAsJsonArray("catacombs")) {
					JsonObject o = f.getAsJsonObject();
					Matcher m = FLOOR.matcher(o.has("name") ? o.get("name").getAsString() : "");
					if (m.find() && num(obj(o, "stats"), "tier_completions") > 0) {
						floor = Math.max(floor, Integer.parseInt(m.group(1)));
					}
				}
			}
			profile.catacombsFloor = floor;
		}

		JsonObject slayers = obj(obj(combined, "slayers"), "data");
		if (!slayers.isEmpty()) {
			for (Map.Entry<String, JsonElement> e : slayers.entrySet()) {
				JsonObject level = obj(e.getValue().getAsJsonObject(), "level");
				profile.slayerXp.put(e.getKey(), num(level, "xp"));
				profile.slayers.put(e.getKey(), (int) num(level, "level"));
			}
			profile.known.add("SLAYER");
		}

		JsonObject crimson = obj(combined, "crimsonIsle");
		if (!crimson.isEmpty()) {
			JsonObject factions = obj(crimson, "factions");
			profile.reputation.put("BARBARIANS", (int) num(factions, "barbariansReputation"));
			profile.reputation.put("MAGES", (int) num(factions, "magesReputation"));
			profile.known.add("REPUTATION");
			JsonObject kuudra = obj(crimson, "kuudra");
			if (kuudra.has("tiers") && kuudra.get("tiers").isJsonArray()) {
				Set<String> tiers = new LinkedHashSet<>();
				for (JsonElement t : kuudra.getAsJsonArray("tiers")) {
					JsonObject o = t.getAsJsonObject();
					String tier = o.has("id") ? o.get("id").getAsString().toUpperCase(Locale.ROOT) : "";
					if (Requirement.KUUDRA_TIERS.contains(tier) && num(o, "kills") > 0) {
						tiers.add(tier);
					}
				}
				profile.kuudraTiers = tiers;
				profile.known.add("KUUDRA");
			}
		}

		JsonObject categories = obj(obj(combined, "collections"), "categories");
		if (!categories.isEmpty()) {
			Map<String, Integer> tiers = new LinkedHashMap<>();
			for (Map.Entry<String, JsonElement> c : categories.entrySet()) {
				JsonElement items = c.getValue().getAsJsonObject().get("items");
				if (items != null && items.isJsonArray()) {
					for (JsonElement i : items.getAsJsonArray()) {
						JsonObject o = i.getAsJsonObject();
						if (o.has("id")) {
							tiers.put(o.get("id").getAsString(), (int) num(o, "tier"));
						}
					}
				}
			}
			profile.collections = tiers;
			profile.known.add("COLLECTION");
		}

		int hotm = (int) num(obj(obj(obj(combined, "skills"), "mining"), "level"), "level");
		if (hotm > 0) {
			profile.hotmTier = hotm;
		}

		JsonObject minionCategories = obj(obj(combined, "minions"), "minions");
		if (!minionCategories.isEmpty()) {
			Map<String, Integer> crafted = new LinkedHashMap<>();
			for (Map.Entry<String, JsonElement> c : minionCategories.entrySet()) {
				JsonElement list = c.getValue().getAsJsonObject().get("minions");
				if (list == null || !list.isJsonArray()) {
					continue;
				}
				for (JsonElement m : list.getAsJsonArray()) {
					JsonObject o = m.getAsJsonObject();
					int best = 0;
					if (o.has("tiers") && o.get("tiers").isJsonArray()) {
						for (JsonElement t : o.getAsJsonArray("tiers")) {
							best = Math.max(best, t.getAsInt());
						}
					}
					if (best > 0 && o.has("name")) {
						String type = o.get("name").getAsString().replaceAll("(?i)\\s*minion$", "").trim()
								.toUpperCase(Locale.ROOT).replace(' ', '_');
						crafted.merge(type, best, Math::max);
					}
				}
			}
			profile.craftedMinions = crafted;
		}

		// SkyCrypt sends item names, not ids, so each name is matched back to an id.
		JsonObject accessories = obj(combined, "accessories");
		if (accessories.has("accessories") && accessories.get("accessories").isJsonArray()) {
			Set<String> ids = new LinkedHashSet<>();
			for (JsonElement a : accessories.getAsJsonArray("accessories")) {
				String id = idForName(a);
				if (id != null) {
					ids.add(id);
				}
			}
			if (!ids.isEmpty()) {
				profile.accessories = ids;
				profile.known.add("ACCESSORIES");
			}
		}
		collectGear(obj(combined, "gear"), profile.ownedItems);
	}

	/** Every item anywhere in the gear section (armor, equipment, wardrobe, weapons). */
	private static void collectGear(JsonElement element, Set<String> out) {
		if (element.isJsonArray()) {
			element.getAsJsonArray().forEach(e -> collectGear(e, out));
		} else if (element.isJsonObject()) {
			JsonObject o = element.getAsJsonObject();
			String id = idForName(o);
			if (id != null) {
				out.add(id);
			}
			for (Map.Entry<String, JsonElement> e : o.entrySet()) {
				if (!e.getKey().equals("lore") && !e.getKey().equals("containsItems")) {
					collectGear(e.getValue(), out);
				}
			}
		}
	}

	/** The item id whose name is the longest match at the end of this display name, which may start with a reforge. */
	private static String idForName(JsonElement item) {
		if (!item.isJsonObject() || !item.getAsJsonObject().has("display_name")) {
			return null;
		}
		String shown = clean(item.getAsJsonObject().get("display_name").getAsString());
		String best = null;
		int bestLength = 0;
		for (Map.Entry<String, String> e : ProgressionClient.get().database().names().entrySet()) {
			String name = clean(e.getValue());
			if (!name.isEmpty() && name.length() > bestLength && (shown.equals(name) || shown.endsWith(" " + name))) {
				best = e.getKey();
				bestLength = name.length();
			}
		}
		return best;
	}

	private static String clean(String name) {
		return name.replaceAll("§.", "").replaceAll("[^\\p{L}\\p{N}' -]", "").trim().toLowerCase(Locale.ROOT);
	}

	private static JsonObject obj(JsonObject parent, String key) {
		return parent != null && parent.has(key) && parent.get(key).isJsonObject() ? parent.getAsJsonObject(key) : new JsonObject();
	}

	private static double num(JsonObject parent, String key) {
		return parent != null && parent.has(key) && parent.get(key).isJsonPrimitive() ? parent.get(key).getAsDouble() : 0;
	}
}
