package dev.sw1ftz.progression;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fairy soul locations from the community-maintained NotEnoughUpdates repo (MIT licensed), which
 * Hypixel's own API doesn't provide. Downloaded at runtime, cached, and refreshed once a day.
 */
public class FairySouls {
	private static final URI SOURCE = URI.create(
			"https://raw.githubusercontent.com/NotEnoughUpdates/NotEnoughUpdates-REPO/master/constants/fairy_souls.json");
	private static final Duration MAX_AGE = Duration.ofHours(24);

	/** Island codes used by the data, with the names SkyBlock shows in the tab list's "Area:" line. */
	private static final Map<String, String> NAMES = Map.ofEntries(
			Map.entry("hub", "Hub"),
			Map.entry("combat_1", "Spider's Den"),
			Map.entry("combat_3", "The End"),
			Map.entry("crimson_isle", "Crimson Isle"),
			Map.entry("foraging_1", "The Park"),
			Map.entry("foraging_2", "Galatea"),
			Map.entry("farming_1", "The Farming Islands"),
			Map.entry("mining_1", "Gold Mine"),
			Map.entry("mining_2", "Deep Caverns"),
			Map.entry("mining_3", "Dwarven Mines"),
			Map.entry("winter", "Jerry's Workshop"),
			Map.entry("dungeon_hub", "Dungeon Hub"),
			Map.entry("fishing_1", "Backwater Bayou"),
			Map.entry("lotus_atoll", "Lotus Atoll"));

	public record Soul(String island, int index, int x, int y, int z) {
		public String key() {
			return island + ":" + x + "," + y + "," + z;
		}

		public double distanceTo(double px, double py, double pz) {
			double dx = x + 0.5 - px, dy = y + 0.5 - py, dz = z + 0.5 - pz;
			return Math.sqrt(dx * dx + dy * dy + dz * dz);
		}
	}

	private volatile Map<String, List<Soul>> islands = Map.of();
	private volatile String status = "Loading fairy souls...";
	private volatile boolean loading;

	public Map<String, List<Soul>> islands() {
		return islands;
	}

	public String status() {
		return status;
	}

	public boolean ready() {
		return !islands.isEmpty();
	}

	public int total() {
		return islands.values().stream().mapToInt(List::size).sum();
	}

	public static String displayName(String island) {
		String name = NAMES.get(island);
		if (name != null) {
			return name;
		}
		String repoName = ProgressionClient.get().repo().areaName(island);
		return repoName != null ? repoName : Requirement.pretty(island);
	}

	public void load() {
		if (loading) {
			return;
		}
		loading = true;
		CompletableFuture.runAsync(() -> {
			Path cache = FabricLoader.getInstance().getConfigDir().resolve("skyblockprogression").resolve("fairy-souls-cache.json");
			try {
				String json;
				boolean fresh = Files.exists(cache) && Files.getLastModifiedTime(cache).toInstant().isAfter(Instant.now().minus(MAX_AGE));
				if (fresh) {
					json = Files.readString(cache);
				} else {
					try {
						HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
						HttpResponse<String> response = http.send(HttpRequest.newBuilder(SOURCE).timeout(Duration.ofSeconds(20)).build(),
								HttpResponse.BodyHandlers.ofString());
						if (response.statusCode() != 200) {
							throw new IllegalStateException("HTTP " + response.statusCode());
						}
						json = response.body();
						Files.createDirectories(cache.getParent());
						Files.writeString(cache, json);
					} catch (Exception e) {
						if (!Files.exists(cache)) {
							throw e;
						}
						json = Files.readString(cache);
					}
				}
				islands = parse(json);
				status = total() + " fairy souls";
			} catch (Exception e) {
				ProgressionClient.LOGGER.warn("Could not load fairy souls", e);
				status = "Couldn't load fairy souls (offline?)";
			} finally {
				loading = false;
			}
		});
	}

	static Map<String, List<Soul>> parse(String json) {
		Map<String, List<Soul>> out = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> island : JsonParser.parseString(json).getAsJsonObject().entrySet()) {
			if (!island.getValue().isJsonArray()) {
				continue;
			}
			List<Soul> souls = new ArrayList<>();
			for (JsonElement coords : island.getValue().getAsJsonArray()) {
				String[] parts = coords.getAsString().split(",");
				souls.add(new Soul(island.getKey(), souls.size() + 1, Integer.parseInt(parts[0].trim()),
						Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim())));
			}
			out.put(island.getKey(), List.copyOf(souls));
		}
		return out;
	}

	/** The island Hypixel's /locraw reported for this world, e.g. "hub" or "foraging_1"; null until it answers. */
	private static volatile String locrawIsland;
	private static Object lastLevel;
	private static int locrawDelay = -1;
	private static long awaitingLocrawUntil;
	private static final Pattern LOCRAW = Pattern.compile("^\\{.*\"gametype\":\"(\\w+)\".*\\}$");
	private static final Pattern LOCRAW_MODE = Pattern.compile("\"mode\":\"(\\w+)\"");

	/** After each world change on Hypixel, quietly asks /locraw which island we're on. Call every tick. */
	public static void tickLocation(Minecraft mc) {
		if (mc.level != lastLevel) {
			lastLevel = mc.level;
			locrawIsland = null;
			locrawDelay = mc.level == null ? -1 : 40;
		}
		if (locrawDelay > 0 && --locrawDelay == 0 && mc.player != null && onHypixel(mc)) {
			awaitingLocrawUntil = System.currentTimeMillis() + 10_000;
			mc.player.connection.sendCommand("locraw");
		}
	}

	private static boolean onHypixel(Minecraft mc) {
		var server = mc.getCurrentServer();
		return server != null && server.ip.toLowerCase(java.util.Locale.ROOT).contains("hypixel");
	}

	/** Reads the /locraw reply. Returns false (hide it) when it's the reply to our own request. */
	public static boolean onLocraw(String text) {
		Matcher m = LOCRAW.matcher(text.trim());
		if (!m.matches()) {
			return true;
		}
		Matcher mode = LOCRAW_MODE.matcher(text);
		if (m.group(1).equals("SKYBLOCK") && mode.find()) {
			locrawIsland = mode.group(1);
		}
		boolean ours = System.currentTimeMillis() < awaitingLocrawUntil;
		awaitingLocrawUntil = 0;
		return !ours;
	}

	/** The island you're on: from /locraw, else SkyBlock's tab list ("Area: Hub"), or null if unknown. */
	public static String currentIsland(Minecraft mc) {
		if (locrawIsland != null) {
			return locrawIsland;
		}
		if (mc.getConnection() == null) {
			return null;
		}
		for (PlayerInfo info : mc.getConnection().getListedOnlinePlayers()) {
			if (info.getTabListDisplayName() == null) {
				continue;
			}
			String line = info.getTabListDisplayName().getString().replaceAll("§.", "").trim();
			int colon = Math.max(line.indexOf("Area:"), line.indexOf("Dungeon:"));
			if (colon >= 0) {
				String area = line.substring(line.indexOf(':', colon) + 1).trim();
				for (Map.Entry<String, String> name : NAMES.entrySet()) {
					if (name.getValue().equalsIgnoreCase(area)) {
						return name.getKey();
					}
				}
				if (area.equalsIgnoreCase("Catacombs") || area.equalsIgnoreCase("Dungeon Hub")) {
					return "dungeon_hub";
				}
			}
		}
		return null;
	}

	/** When you pick up a soul (or touch one you already have), mark the closest soul on this island as found. */
	public void onChat(String message, PlayerProfile profile) {
		if (!message.contains("You found a Fairy Soul") && !message.contains("You have already found that Fairy Soul")) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		String island = currentIsland(mc);
		if (player == null || island == null) {
			return;
		}
		Soul closest = null;
		double best = 8;
		for (Soul soul : islands.getOrDefault(island, List.of())) {
			double d = soul.distanceTo(player.getX(), player.getY(), player.getZ());
			if (d < best) {
				best = d;
				closest = soul;
			}
		}
		if (closest != null && profile.foundSouls.add(closest.key())) {
			ProgressionClient.get().profileChanged();
		}
	}

	private static final int PINK = 0xFFFF55FF;
	private String trackedIsland;
	private int islandCheckCooldown;

	/** The island used for outlines; re-read from the tab list once a second. */
	public String trackedIsland(Minecraft mc) {
		if (--islandCheckCooldown <= 0) {
			islandCheckCooldown = 20;
			String forced = System.getProperty("progression.devIsland");
			trackedIsland = forced != null ? forced : currentIsland(mc);
		}
		return trackedIsland;
	}

	/**
	 * Draws a box, a beam and a distance label on every fairy soul you haven't found on this island.
	 * Uses Minecraft's gizmo system, which shows them through walls. Called once per client tick.
	 */
	public void drawOutlines(Minecraft mc, PlayerProfile profile) {
		if (!profile.soulOutlines || mc.player == null || mc.level == null || islands.isEmpty()) {
			return;
		}
		String island = trackedIsland(mc);
		if (island == null) {
			return;
		}
		LocalPlayer player = mc.player;
		try (var collection = mc.collectPerTickGizmos()) {
			for (Soul soul : islands.getOrDefault(island, List.of())) {
				if (profile.foundSouls.contains(soul.key())) {
					continue;
				}
				double distance = soul.distanceTo(player.getX(), player.getY(), player.getZ());
				net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(soul.x(), soul.y(), soul.z(),
						soul.x() + 1, soul.y() + 1, soul.z() + 1).inflate(0.05);
				net.minecraft.gizmos.Gizmos.cuboid(box, net.minecraft.gizmos.GizmoStyle.strokeAndFill(PINK, 2.5f, 0x40FF55FF))
						.setAlwaysOnTop();
				net.minecraft.world.phys.Vec3 top = new net.minecraft.world.phys.Vec3(soul.x() + 0.5, soul.y() + 1.05, soul.z() + 0.5);
				if (distance > 10) {
					// A tall beam so far-away souls are easy to spot.
					net.minecraft.gizmos.Gizmos.line(top, top.add(0, 48, 0), 0xC0FF55FF, 3f).setAlwaysOnTop();
				}
				// Labels only for nearby souls, sized to stay readable without covering the screen.
				if (distance <= 60) {
					net.minecraft.gizmos.Gizmos.billboardText("Fairy Soul · " + Math.round(distance) + "m", top.add(0, 0.6, 0),
						net.minecraft.gizmos.TextGizmo.Style.forColorAndCentered(PINK)
								.withScale((float) (net.minecraft.gizmos.TextGizmo.Style.DEFAULT_SCALE * Math.max(2, distance / 2.5)))).setAlwaysOnTop();
				}
			}
		}
	}
}
