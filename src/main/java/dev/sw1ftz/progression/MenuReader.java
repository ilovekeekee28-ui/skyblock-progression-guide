package dev.sw1ftz.progression;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Remembers stats from SkyBlock menus as you open them, for players without an API key:
 * /skills, the slayer menu, collection menus, the accessory bag and minions.
 */
public class MenuReader {
	private static final Pattern SKILL_ITEM = Pattern.compile(
			"^(Farming|Mining|Combat|Foraging|Fishing|Enchanting|Alchemy|Taming|Carpentry|Runecrafting|Social|Hunting) ([IVXLC]+|\\d+)$");
	private static final Pattern NAME_AND_TIER = Pattern.compile("^(.+?) ([IVXLC]+|\\d+)$");
	private static final Pattern LEVEL_IN_LORE = Pattern.compile("(?:LVL|Level|Lvl)[: ]+(\\d+)");
	private static final Pattern MINION_TITLE = Pattern.compile("^(.+) Minion ([IVXLC]+)$");
	private static final Map<String, String> SLAYER_BOSSES = Map.of(
			"Revenant Horror", "zombie", "Tarantula Broodfather", "spider", "Sven Packmaster", "wolf",
			"Voidgloom Seraph", "enderman", "Inferno Demonlord", "blaze", "Riftstalker Bloodfiend", "vampire");
	private static final Map<Character, Integer> ROMAN = Map.of('I', 1, 'V', 5, 'X', 10, 'L', 50, 'C', 100);

	private Screen lastScreen;
	private int ticksOpen;

	private int inventoryCooldown;

	/** Remember the SkyBlock items you're carrying and wearing, every few seconds. */
	private void readOwnInventory(Minecraft mc, PlayerProfile profile) {
		if (--inventoryCooldown > 0 || mc.player == null) {
			return;
		}
		inventoryCooldown = 100;
		boolean changed = false;
		var inventory = mc.player.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			String id = skyblockId(inventory.getItem(i));
			if (!id.isEmpty()) {
				changed |= profile.ownedItems.add(id);
			}
		}
		if (changed) {
			ProgressionClient.get().profileChanged();
		}
	}

	public void tick(Minecraft mc, PlayerProfile profile) {
		readOwnInventory(mc, profile);
		if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) {
			lastScreen = null;
			return;
		}
		if (screen != lastScreen) {
			lastScreen = screen;
			ticksOpen = 0;
		}
		// Items arrive a few ticks after a menu opens; read once after 5 ticks, then again every second.
		if (++ticksOpen != 5 && ticksOpen % 20 != 0) {
			return;
		}
		String title = screen.getTitle().getString().replaceAll("§.", "").trim();
		List<Slot> top = topSlots(screen);
		boolean changed;
		if (title.contains("Your Skills")) {
			changed = readSkills(top, profile);
		} else if (title.contains("Slayer")) {
			changed = readSlayers(top, profile);
		} else if (title.contains("Collection")) {
			changed = readCollections(top, profile);
		} else if (title.startsWith("Accessory Bag")) {
			changed = readAccessories(top, profile);
		} else {
			Matcher minion = MINION_TITLE.matcher(title);
			changed = minion.matches() && readMinion(mc, minion.group(1), parseLevel(minion.group(2)), top, profile);
		}
		if (changed) {
			ProgressionClient.get().profileChanged();
		}
	}

	/** The chest part of the menu, without your own inventory. */
	private static List<Slot> topSlots(AbstractContainerScreen<?> screen) {
		List<Slot> out = new ArrayList<>();
		for (Slot slot : screen.getMenu().slots) {
			if (!(slot.container instanceof net.minecraft.world.entity.player.Inventory)) {
				out.add(slot);
			}
		}
		return out;
	}

	private static String name(ItemStack stack) {
		return stack.getHoverName().getString().replaceAll("§.", "").trim();
	}

	private static List<String> lore(ItemStack stack) {
		ItemLore lore = stack.get(DataComponents.LORE);
		List<String> out = new ArrayList<>();
		if (lore != null) {
			for (Component line : lore.lines()) {
				out.add(line.getString().replaceAll("§.", ""));
			}
		}
		return out;
	}

	/** SkyBlock's own item id, e.g. "ZOMBIE_TALISMAN", or "" for menu decorations. */
	static String skyblockId(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) {
			return "";
		}
		var tag = data.copyTag();
		String id = tag.getStringOr("id", "");
		return id.isEmpty() ? tag.getCompoundOrEmpty("ExtraAttributes").getStringOr("id", "") : id;
	}

	private boolean readSkills(List<Slot> slots, PlayerProfile profile) {
		boolean changed = false;
		for (Slot slot : slots) {
			Matcher m = SKILL_ITEM.matcher(name(slot.getItem()));
			if (m.matches()) {
				Integer old = profile.skills.put(m.group(1).toUpperCase(Locale.ROOT), parseLevel(m.group(2)));
				changed |= old == null || old != parseLevel(m.group(2));
			}
		}
		return changed;
	}

	private boolean readSlayers(List<Slot> slots, PlayerProfile profile) {
		boolean changed = false;
		for (Slot slot : slots) {
			String type = SLAYER_BOSSES.get(name(slot.getItem()));
			if (type == null) {
				continue;
			}
			for (String line : lore(slot.getItem())) {
				Matcher m = LEVEL_IN_LORE.matcher(line);
				if (m.find()) {
					int level = Integer.parseInt(m.group(1));
					Integer old = profile.slayers.put(type, level);
					changed |= old == null || old != level;
					break;
				}
			}
		}
		if (changed) {
			profile.known.add("SLAYER");
		}
		return changed;
	}

	private boolean readCollections(List<Slot> slots, PlayerProfile profile) {
		RepoData repo = ProgressionClient.get().repo();
		boolean changed = false;
		for (Slot slot : slots) {
			Matcher m = NAME_AND_TIER.matcher(name(slot.getItem()));
			if (!m.matches()) {
				continue;
			}
			String id = repo.collectionIdForName(m.group(1));
			if (id != null) {
				int tier = parseLevel(m.group(2));
				Integer old = profile.collections.put(id, tier);
				changed |= old == null || old != tier;
			}
		}
		return changed;
	}

	private boolean readAccessories(List<Slot> slots, PlayerProfile profile) {
		boolean changed = false;
		for (Slot slot : slots) {
			String id = skyblockId(slot.getItem());
			if (!id.isEmpty() && !slot.getItem().getItem().toString().contains("glass_pane")) {
				changed |= profile.accessories.add(id);
			}
		}
		if (changed) {
			profile.known.add("ACCESSORIES");
		}
		return changed;
	}

	/**
	 * Records a minion's type, tier and how full it is. Storage is the 3x6 block on the right side of
	 * Hypixel's minion menu; glass panes there are locked slots.
	 */
	private boolean readMinion(Minecraft mc, String displayType, int tier, List<Slot> slots, PlayerProfile profile) {
		if (mc.player == null) {
			return false;
		}
		String type = minionType(displayType);
		int stored = 0;
		for (int row = 2; row <= 4; row++) {
			for (int col = 3; col <= 8; col++) {
				int index = row * 9 + col;
				if (index < slots.size()) {
					ItemStack stack = slots.get(index).getItem();
					if (!stack.isEmpty() && !stack.getItem().toString().contains("glass_pane")) {
						stored += stack.getCount();
					}
				}
			}
		}
		BlockPos pos = mc.player.blockPosition();
		PlayerProfile.MinionState state = null;
		// The same minion if it's the same type within a few blocks of where we last opened it.
		for (PlayerProfile.MinionState m : profile.minions) {
			if (m.type.equals(type) && Math.abs(m.x - pos.getX()) <= 4 && Math.abs(m.y - pos.getY()) <= 4 && Math.abs(m.z - pos.getZ()) <= 4) {
				state = m;
				break;
			}
		}
		if (state == null) {
			state = new PlayerProfile.MinionState();
			state.type = type;
			profile.minions.add(state);
		}
		boolean changed = state.tier != tier || state.stored != stored;
		state.tier = tier;
		state.stored = stored;
		state.x = pos.getX();
		state.y = pos.getY();
		state.z = pos.getZ();
		state.seenAt = System.currentTimeMillis();
		state.alerted = false;
		RepoData.MinionTier info = ProgressionClient.get().repo().minionTier(type + "_GENERATOR_" + tier);
		if (info != null && info.storage() > 0) {
			state.capacity = info.storage();
		}
		profile.craftedMinions.merge(type, tier, Math::max);
		return changed || state.seenAt > 0;
	}

	/** "Cave Spider" -> the repo's key style "CAVESPIDER" when that's what it uses, else "CAVE_SPIDER". */
	static String minionType(String displayType) {
		String underscored = displayType.toUpperCase(Locale.ROOT).replace(' ', '_');
		String squashed = underscored.replace("_", "");
		for (String generator : ProgressionClient.get().repo().minionMaxTier().keySet()) {
			String base = generator.replace("_GENERATOR", "");
			if (base.equals(underscored) || base.replace("_", "").equals(squashed)) {
				return base;
			}
		}
		return underscored;
	}

	static int parseLevel(String text) {
		if (Character.isDigit(text.charAt(0))) {
			return Integer.parseInt(text);
		}
		int total = 0;
		for (int i = 0; i < text.length(); i++) {
			int value = ROMAN.getOrDefault(text.charAt(i), 0);
			int next = i + 1 < text.length() ? ROMAN.getOrDefault(text.charAt(i + 1), 0) : 0;
			total += value < next ? -value : value;
		}
		return total;
	}
}
