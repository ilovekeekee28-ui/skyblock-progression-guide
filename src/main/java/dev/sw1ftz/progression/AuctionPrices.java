package dev.sw1ftz.progression;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Lowest buy-it-now price per item, from scanning every page of Hypixel's public auction list.
 * That's roughly 100 MB of downloads, so it only runs when you press the scan button, and the result
 * is kept for two hours (also across restarts).
 */
public class AuctionPrices {
	private static final String AUCTIONS = "https://api.hypixel.net/v2/skyblock/auctions?page=";
	private static final long MAX_AGE_MS = 2 * 60 * 60_000;
	private static final Gson GSON = new Gson();

	private volatile Map<String, Double> lowestBin = Map.of();
	private volatile long scannedAt;
	private volatile String status = "";
	private volatile boolean scanning;

	public AuctionPrices() {
		try {
			Path cache = cache();
			if (Files.exists(cache) && System.currentTimeMillis() - Files.getLastModifiedTime(cache).toMillis() < MAX_AGE_MS) {
				lowestBin = GSON.fromJson(Files.readString(cache), new TypeToken<Map<String, Double>>() {
				}.getType());
				scannedAt = Files.getLastModifiedTime(cache).toMillis();
			}
		} catch (Exception e) {
			ProgressionClient.LOGGER.warn("Could not read auction cache", e);
		}
	}

	private static Path cache() {
		return FabricLoader.getInstance().getConfigDir().resolve("skyblockprogression").resolve("auction-prices.json");
	}

	public Double lowestBin(String id) {
		return lowestBin.get(id);
	}

	public boolean hasPrices() {
		return !lowestBin.isEmpty();
	}

	public boolean scanning() {
		return scanning;
	}

	public long ageMinutes() {
		return scannedAt == 0 ? -1 : (System.currentTimeMillis() - scannedAt) / 60_000;
	}

	public String status() {
		return status;
	}

	public void scan() {
		if (scanning) {
			return;
		}
		scanning = true;
		CompletableFuture.runAsync(() -> {
			HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
			Map<String, Double> lowest = new HashMap<>();
			try {
				int page = 0;
				int totalPages = 1;
				while (page < totalPages) {
					status = "Scanning auctions " + (page + 1) + "/" + totalPages + "...";
					if (page > 0) {
						// Spread the pages out instead of downloading them all at once.
						Thread.sleep(500);
					}
					HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(AUCTIONS + page))
							.timeout(Duration.ofSeconds(30)).header("User-Agent", "SkyBlockProgression-Mod/1.0").build(),
							HttpResponse.BodyHandlers.ofString());
					if (response.statusCode() != 200) {
						throw new IllegalStateException("HTTP " + response.statusCode());
					}
					JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
					totalPages = root.get("totalPages").getAsInt();
					for (JsonElement a : root.getAsJsonArray("auctions")) {
						JsonObject auction = a.getAsJsonObject();
						if (!auction.has("bin") || !auction.get("bin").getAsBoolean()) {
							continue;
						}
						double price = auction.get("starting_bid").getAsDouble();
						Set<String> ids = HypixelApi.itemIds(auction.get("item_bytes").getAsString());
						for (String id : ids) {
							lowest.merge(id, price, Math::min);
						}
					}
					page++;
				}
				lowestBin = Map.copyOf(lowest);
				scannedAt = System.currentTimeMillis();
				Files.createDirectories(cache().getParent());
				Files.writeString(cache(), GSON.toJson(lowest));
				status = String.format("Prices for %,d items", lowest.size());
			} catch (Exception e) {
				ProgressionClient.LOGGER.warn("Auction scan failed", e);
				status = "Auction scan failed";
			} finally {
				scanning = false;
			}
		});
	}
}
