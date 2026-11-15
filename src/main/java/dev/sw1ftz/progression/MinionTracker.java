package dev.sw1ftz.progression;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Map;

/**
 * Estimates for the minions you've opened: how fast they fill, what they earn, when they'll be full
 * (with a chat alert), and how long until their income pays for the next tier.
 *
 * Hypixel doesn't share live minion data, so this works from what was in the minion when you last
 * opened it, plus its speed from the item data. Fuel, upgrades and multi-drop minions change the real
 * numbers, so treat these as estimates.
 */
public class MinionTracker {
	/** Product item id when it isn't simply the minion's type. */
	private static final Map<String, String> PRODUCTS = Map.ofEntries(
			Map.entry("NETHER_WARTS", "NETHER_STALK"),
			Map.entry("COCOA", "INK_SACK:3"),
			Map.entry("SNOW", "SNOW_BALL"),
			Map.entry("CLAY", "CLAY_BALL"),
			Map.entry("LAPIS", "INK_SACK:4"),
			Map.entry("REDSTONE", "REDSTONE"),
			Map.entry("GLOWSTONE", "GLOWSTONE_DUST"),
			Map.entry("QUARTZ", "QUARTZ"),
			Map.entry("CAVESPIDER", "SPIDER_EYE"),
			Map.entry("SPIDER", "STRING"),
			Map.entry("ZOMBIE", "ROTTEN_FLESH"),
			Map.entry("SKELETON", "BONE"),
			Map.entry("CREEPER", "SULPHUR"),
			Map.entry("ENDERMAN", "ENDER_PEARL"),
			Map.entry("BLAZE", "BLAZE_ROD"),
			Map.entry("MAGMA_CUBE", "MAGMA_CREAM"),
			Map.entry("GHAST", "GHAST_TEAR"),
			Map.entry("SLIME", "SLIME_BALL"),
			Map.entry("CHICKEN", "RAW_CHICKEN"),
			Map.entry("COW", "RAW_BEEF"),
			Map.entry("PIG", "PORK"),
			Map.entry("SHEEP", "MUTTON"),
			Map.entry("RABBIT", "RABBIT"),
			Map.entry("FISHING", "RAW_FISH"),
			Map.entry("MITHRIL", "MITHRIL_ORE"),
			Map.entry("HARD_STONE", "HARD_STONE"),
			Map.entry("MUSHROOM", "RED_MUSHROOM"),
			Map.entry("SUGAR_CANE", "SUGAR_CANE"),
			Map.entry("OAK", "LOG"),
			Map.entry("SPRUCE", "LOG:1"),
			Map.entry("BIRCH", "LOG:2"),
			Map.entry("JUNGLE", "LOG:3"),
			Map.entry("ACACIA", "LOG_2"),
			Map.entry("DARK_OAK", "LOG_2:1"));

	private int cooldown;

	/** Estimated stats for one minion; any value may be NaN when unknown. */
	public record Estimate(double itemsPerHour, double coinsPerHour, long fullAtMillis, double nextTierCost, double hoursToPayNextTier) {
	}

	public static String productId(String type) {
		return PRODUCTS.getOrDefault(type, type);
	}

	public Estimate estimate(PlayerProfile.MinionState minion) {
		ProgressionClient client = ProgressionClient.get();
		RepoData.MinionTier info = client.repo().minionTier(minion.type + "_GENERATOR_" + minion.tier);
		if (info == null || info.secondsPerAction() <= 0) {
			return new Estimate(Double.NaN, Double.NaN, -1, Double.NaN, Double.NaN);
		}
		// Most minions make one item every two actions (place/spawn, then harvest).
		double itemsPerHour = 3600 / (info.secondsPerAction() * 2);
		String product = productId(minion.type);
		Double bazaar = client.prices().sellPrice(product);
		// Whichever pays more: the bazaar, or selling to an NPC.
		double price = Math.max(bazaar != null ? bazaar : 0, client.database().npcPrice(product));
		double coinsPerHour = itemsPerHour * price;

		int capacity = minion.capacity > 0 ? minion.capacity : info.storage();
		long fullAt = -1;
		if (capacity > 0) {
			double hoursLeft = Math.max(0, capacity - minion.stored) / itemsPerHour;
			fullAt = minion.seenAt + (long) (hoursLeft * 3_600_000);
		}

		double nextCost = Double.NaN;
		int maxTier = client.repo().minionMaxTier().getOrDefault(minion.type + "_GENERATOR", 11);
		if (minion.tier < maxTier) {
			RepoData.MinionTier next = client.repo().minionTier(minion.type + "_GENERATOR_" + (minion.tier + 1));
			if (next != null && !next.recipe().isEmpty()) {
				nextCost = 0;
				for (Map.Entry<String, Integer> part : next.recipe().entrySet()) {
					if (part.getKey().contains("_GENERATOR_")) {
						continue; // the minion you already have
					}
					Double each = client.prices().buyPrice(part.getKey());
					if (each == null) {
						each = client.auctions().lowestBin(part.getKey());
					}
					if (each == null) {
						nextCost = Double.NaN;
						break;
					}
					nextCost += each * part.getValue();
				}
			}
		}
		double hours = coinsPerHour > 0 && !Double.isNaN(nextCost) ? nextCost / coinsPerHour : Double.NaN;
		return new Estimate(itemsPerHour, coinsPerHour, fullAt, nextCost, hours);
	}

	/** Once every five seconds, alert about minions that should be full by now. */
	public void tick(Minecraft mc, PlayerProfile profile) {
		if (--cooldown > 0 || mc.player == null) {
			return;
		}
		cooldown = 100;
		if (!profile.minionAlerts) {
			return;
		}
		boolean changed = false;
		long now = System.currentTimeMillis();
		for (PlayerProfile.MinionState minion : profile.minions) {
			if (minion.alerted) {
				continue;
			}
			Estimate e = estimate(minion);
			if (e.fullAtMillis() > 0 && now >= e.fullAtMillis()) {
				minion.alerted = true;
				changed = true;
				mc.player.sendSystemMessage(Component.literal("[Progression] ").withStyle(ChatFormatting.GOLD)
						.append(Component.literal("Your " + Requirement.pretty(minion.type) + " Minion " + minion.tier
								+ " is probably full now. Empty it to keep it working!").withStyle(ChatFormatting.YELLOW)));
			}
		}
		if (changed) {
			ProgressionClient.get().profileChanged();
		}
	}
}
