package dev.sw1ftz.progression;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-written areas and money-making methods (Hypixel's API has neither). Bundled in the mod;
 * a copy in config/skyblockprogression/guide.json overrides it, so it can be updated without a new jar.
 */
public final class Guide {
	/** A place to train a skill, or a way to make money. Later entries in the file are better. */
	public record Entry(String name, String info, List<Requirement> requires, List<String> products, int order) {
		public boolean unlocked(PlayerProfile profile) {
			return requires.stream().allMatch(profile::meets);
		}

		public List<String> missing(PlayerProfile profile) {
			List<String> out = new ArrayList<>();
			for (Requirement r : requires) {
				if (!profile.meets(r)) {
					out.add(r.text());
				}
			}
			return out;
		}
	}

	/** Best unlocked entry and the next one to work towards; either can be null. */
	public record Progress(Entry best, Entry next) {
	}

	private final Map<String, List<Entry>> areas;
	private final List<Entry> money;

	private Guide(Map<String, List<Entry>> areas, List<Entry> money) {
		this.areas = areas;
		this.money = money;
	}

	public List<Entry> money() {
		return money;
	}

	public Progress area(SkillCategory skill, PlayerProfile profile) {
		return progress(areas.getOrDefault(skill.name(), List.of()), profile);
	}

	static Progress progress(List<Entry> entries, PlayerProfile profile) {
		Entry best = null;
		for (Entry e : entries) {
			if (e.unlocked(profile)) {
				best = e;
			}
		}
		int after = best == null ? -1 : best.order();
		Entry next = entries.stream().filter(e -> e.order() > after && !e.unlocked(profile)).findFirst().orElse(null);
		return new Progress(best, next);
	}

	public static Guide load() {
		String json = null;
		Path override = FabricLoader.getInstance().getConfigDir().resolve("skyblockprogression").resolve("guide.json");
		try {
			if (Files.exists(override)) {
				json = Files.readString(override);
			}
		} catch (Exception e) {
			ProgressionClient.LOGGER.warn("Could not read {}, using the built-in guide", override, e);
		}
		if (json == null) {
			try (InputStream in = Guide.class.getResourceAsStream("/assets/skyblockprogression/guide.json")) {
				json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			} catch (Exception e) {
				ProgressionClient.LOGGER.error("Built-in guide is missing", e);
				return new Guide(Map.of(), List.of());
			}
		}
		return parse(json);
	}

	static Guide parse(String json) {
		JsonObject root = JsonParser.parseString(json).getAsJsonObject();
		Map<String, List<Entry>> areas = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> skill : root.getAsJsonObject("areas").entrySet()) {
			areas.put(skill.getKey(), entries(skill.getValue()));
		}
		return new Guide(areas, entries(root.get("money")));
	}

	private static List<Entry> entries(JsonElement array) {
		List<Entry> out = new ArrayList<>();
		for (JsonElement element : array.getAsJsonArray()) {
			JsonObject o = element.getAsJsonObject();
			List<Requirement> requires = new ArrayList<>();
			for (JsonElement r : o.getAsJsonArray("requires")) {
				requires.add(Requirement.parse(r.getAsJsonObject()));
			}
			List<String> products = new ArrayList<>();
			if (o.has("products")) {
				for (JsonElement p : o.getAsJsonArray("products")) {
					products.add(p.getAsString());
				}
			}
			out.add(new Entry(o.get("name").getAsString(), o.get("info").getAsString(), List.copyOf(requires),
					List.copyOf(products), out.size()));
		}
		return List.copyOf(out);
	}
}
