package dev.sw1ftz.progression;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Live instant-sell prices from Hypixel's public bazaar endpoint, fetched when the Money tab is opened. */
public class BazaarPrices {
	private static final URI BAZAAR_URI = URI.create("https://api.hypixel.net/v2/skyblock/bazaar");
	private static final long MAX_AGE_MS = 3 * 60_000;

	private volatile Map<String, Double> sellPrices = Map.of();
	private volatile Map<String, Double> buyPrices = Map.of();
	private volatile long fetchedAt;
	private volatile boolean loading;

	/** Price you'd get selling one item instantly, or null if it isn't on the bazaar (or prices aren't loaded yet). */
	public Double sellPrice(String productId) {
		return sellPrices.get(productId);
	}

	/** Price to buy one instantly, or null. */
	public Double buyPrice(String productId) {
		Double price = buyPrices.get(productId);
		return price == null || price <= 0 ? null : price;
	}

	public boolean loaded() {
		return fetchedAt != 0;
	}

	public void refreshIfOld() {
		if (loading || System.currentTimeMillis() - fetchedAt < MAX_AGE_MS) {
			return;
		}
		loading = true;
		CompletableFuture.runAsync(() -> {
			try {
				HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
				HttpRequest request = HttpRequest.newBuilder(BAZAAR_URI)
						.timeout(Duration.ofSeconds(20))
						.header("User-Agent", "SkyBlockProgression-Mod/1.0")
						.build();
				HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
				Map<String, Double> prices = new HashMap<>();
				Map<String, Double> buys = new HashMap<>();
				for (Map.Entry<String, JsonElement> product : JsonParser.parseString(response.body()).getAsJsonObject()
						.getAsJsonObject("products").entrySet()) {
					JsonObject quick = product.getValue().getAsJsonObject().getAsJsonObject("quick_status");
					if (quick != null) {
						prices.put(product.getKey(), quick.get("sellPrice").getAsDouble());
						buys.put(product.getKey(), quick.get("buyPrice").getAsDouble());
					}
				}
				sellPrices = Map.copyOf(prices);
				buyPrices = Map.copyOf(buys);
				fetchedAt = System.currentTimeMillis();
			} catch (Exception e) {
				ProgressionClient.LOGGER.warn("Bazaar prices failed", e);
			} finally {
				loading = false;
			}
		});
	}
}
