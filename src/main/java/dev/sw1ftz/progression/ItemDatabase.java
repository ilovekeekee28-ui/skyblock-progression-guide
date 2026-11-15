package dev.sw1ftz.progression;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Loads Hypixel's public SkyBlock item list (no API key needed) and keeps the gear from it.
 * The list is cached on disk and refreshed once a day, so new items appear automatically.
 */
public class ItemDatabase {
	private static final URI ITEMS_URI = URI.create("https://api.hypixel.net/v2/resources/skyblock/items");
	private static Path cache() {
		return FabricLoader.getInstance().getConfigDir().resolve("skyblockprogression").resolve("items-cache.json");
	}
	private static final Duration MAX_AGE = Duration.ofHours(24);
	private static final Set<String> GEAR = Set.of("HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS", "SWORD", "BOW", "LONGSWORD",
			"AXE", "PICKAXE", "DRILL", "GAUNTLET", "FARMING_TOOL", "FISHING_ROD", "FISHING_NET", "LASSO", "VACUUM");

	private volatile List<GearItem> items = List.of();
	private volatile List<Accessory> accessories = List.of();
	private volatile Map<String, Double> npcPrices = Map.of();
	private volatile Map<String, String> names = Map.of();

	/** An accessory (talisman, ring, artifact, ...) and what it needs. */
	public record Accessory(String id, String name, String rarity, List<Requirement> requirements) {
	}

	public List<Accessory> accessories() {
		return accessories;
	}

	/** What an NPC pays for one, or 0. */
	public double npcPrice(String id) {
		return npcPrices.getOrDefault(id, 0.0);
	}

	/** Display name for any item id, falling back to a prettified id. */
	/** Every item id with its display name, for matching names back to ids. */
	public Map<String, String> names() {
		return names;
	}

	public String name(String id) {
		String name = names.get(id);
		return name != null ? name : Requirement.pretty(id);
	}
	private volatile String status = "Loading item data...";
	private volatile boolean loading;

	public List<GearItem> items() {
		return items;
	}

	public String status() {
		return status;
	}

	public boolean ready() {
		return !items.isEmpty();
	}

	public void load(boolean forceDownload) {
		if (loading) {
			return;
		}
		loading = true;
		CompletableFuture.runAsync(() -> {
			try {
				String json = null;
				boolean fresh = Files.exists(cache())
						&& Files.getLastModifiedTime(cache()).toInstant().isAfter(java.time.Instant.now().minus(MAX_AGE));
				if (!forceDownload && fresh) {
					json = Files.readString(cache());
				} else {
					status = "Downloading item data from Hypixel...";
					try {
						json = download();
						Files.createDirectories(cache().getParent());
						Files.writeString(cache(), json);
					} catch (Exception e) {
						ProgressionClient.LOGGER.warn("Item download failed, trying the cache", e);
						if (Files.exists(cache())) {
							json = Files.readString(cache());
						} else {
							throw e;
						}
					}
				}
				parseExtras(json);
				items = parse(json);
				status = String.format(Locale.ROOT, "%,d gear items", items.size());
			} catch (Exception e) {
				ProgressionClient.LOGGER.warn("Could not load SkyBlock items", e);
				status = "Couldn't load item data (offline?)";
			} finally {
				loading = false;
			}
		});
	}

	private static String download() throws Exception {
		HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
		HttpRequest request = HttpRequest.newBuilder(ITEMS_URI)
				.timeout(Duration.ofSeconds(30))
				.header("User-Agent", "SkyBlockProgression-Mod/1.0")
				.build();
		HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			throw new IllegalStateException("HTTP " + response.statusCode());
		}
		return response.body();
	}

	/** Accessories, NPC sell prices and display names for every item. */
	private void parseExtras(String json) {
		List<Accessory> found = new ArrayList<>();
		Map<String, Double> npc = new HashMap<>();
		Map<String, String> allNames = new HashMap<>();
		for (JsonElement element : JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("items")) {
			JsonObject o = element.getAsJsonObject();
			String id = o.get("id").getAsString();
			allNames.put(id, o.get("name").getAsString().replaceAll("§.", ""));
			if (o.has("npc_sell_price")) {
				npc.put(id, o.get("npc_sell_price").getAsDouble());
			}
			if (o.has("category") && o.get("category").getAsString().equals("ACCESSORY")) {
				List<Requirement> requirements = new ArrayList<>();
				if (o.has("requirements")) {
					for (JsonElement r : o.getAsJsonArray("requirements")) {
						requirements.add(Requirement.parse(r.getAsJsonObject()));
					}
				}
				found.add(new Accessory(id, allNames.get(id), o.has("tier") ? o.get("tier").getAsString() : "COMMON",
						List.copyOf(requirements)));
			}
		}
		accessories = List.copyOf(found);
		npcPrices = Map.copyOf(npc);
		names = Map.copyOf(allNames);
	}

	static List<GearItem> parse(String json) {
		List<GearItem> result = new ArrayList<>();
		for (JsonElement element : JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("items")) {
			JsonObject o = element.getAsJsonObject();
			if (!o.has("category") || !GEAR.contains(o.get("category").getAsString()) || !o.has("museum_data")) {
				continue;
			}
			JsonObject museum = o.getAsJsonObject("museum_data");
			if (!museum.has("category")) {
				continue;
			}

			Map<String, Double> stats = new HashMap<>();
			if (o.has("stats")) {
				for (Map.Entry<String, JsonElement> stat : o.getAsJsonObject("stats").entrySet()) {
					stats.merge(stat.getKey().toLowerCase(Locale.ROOT), stat.getValue().getAsDouble(), Double::sum);
				}
			}

			List<Requirement> requirements = new ArrayList<>();
			if (o.has("requirements")) {
				for (JsonElement r : o.getAsJsonArray("requirements")) {
					requirements.add(Requirement.parse(r.getAsJsonObject()));
				}
			}

			String setId = null;
			if (museum.has("armor_set_donation_xp")) {
				for (String key : museum.getAsJsonObject("armor_set_donation_xp").keySet()) {
					setId = key;
				}
			}

			result.add(new GearItem(
					o.get("id").getAsString(),
					o.get("name").getAsString().replaceAll("§.", ""),
					o.get("category").getAsString(),
					o.has("tier") ? o.get("tier").getAsString() : "COMMON",
					museum.get("category").getAsString(),
					museum.has("game_stage") ? GearItem.stageIndex(museum.get("game_stage").getAsString()) : -1,
					Map.copyOf(stats),
					List.copyOf(requirements),
					setId));
		}
		return List.copyOf(result);
	}
}
