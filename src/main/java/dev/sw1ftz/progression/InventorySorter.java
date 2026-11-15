package dev.sw1ftz.progression;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Sorts your main inventory, ender chest or backpack by rarity, highest first, with the same items together.
 * Off until you run /sbprog autosort. Never touches the hotbar, shops or other menus, and never merges stacks:
 * it only swaps different items one click at a time.
 */
public final class InventorySorter {
	private static final int TICKS_PER_CLICK = 3;
	private static final int STUCK_TICKS = 60;
	private static final int MAX_SWAPS = 150;
	/** Highest first. Longer names come first where one contains another. */
	private static final List<String> RARITIES = List.of("VERY SPECIAL", "SPECIAL", "DIVINE", "MYTHIC", "LEGENDARY", "EPIC", "RARE", "UNCOMMON", "COMMON");

	private boolean keyWasDown;
	private Screen sorting;
	private int containerId;
	private List<Integer> slots = List.of();
	private final Deque<Integer> clicks = new ArrayDeque<>();
	private int wait, stuck, swaps;

	public void tick(Minecraft mc, KeyMapping key, PlayerProfile profile) {
		boolean down = !key.isUnbound() && net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper.getBoundKeyOf(key).getType() == InputConstants.Type.KEYSYM
				&& InputConstants.isKeyDown(mc.getWindow(), net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper.getBoundKeyOf(key).getValue());
		boolean pressed = down && !keyWasDown;
		keyWasDown = down;
		if (sorting != null) {
			if (pressed) {
				stop(mc, "Sort stopped.");
				return;
			}
			run(mc);
		} else if (pressed) {
			start(mc, profile);
		}
	}

	private void start(Minecraft mc, PlayerProfile profile) {
		List<Integer> targets = targets(mc.screen);
		if (targets == null) {
			return; // shops and menus: do nothing at all
		}
		if (!profile.autoSort) {
			say(mc, "Auto-sort is off. Turn it on with /sbprog autosort (can break Hypixel's mod rules).", ChatFormatting.YELLOW);
			return;
		}
		sorting = mc.screen;
		containerId = mc.player.containerMenu.containerId;
		slots = targets;
		clicks.clear();
		wait = stuck = swaps = 0;
	}

	/** Slots to sort on this screen, or null if it isn't one we sort. */
	private static List<Integer> targets(Screen screen) {
		List<Integer> out = new ArrayList<>();
		if (screen instanceof InventoryScreen) {
			for (int i = 9; i <= 35; i++) { // 36-44 are the hotbar
				out.add(i);
			}
			return out;
		}
		if (screen instanceof ContainerScreen chest && chest.getMenu() instanceof ChestMenu menu) {
			String title = screen.getTitle().getString();
			if (title.startsWith("Ender Chest") || title.contains("Backpack")) {
				// The top row of these holds Hypixel's page buttons.
				for (int i = 9; i < menu.getRowCount() * 9; i++) {
					out.add(i);
				}
				return out;
			}
		}
		return null;
	}

	private void run(Minecraft mc) {
		AbstractContainerMenu menu = mc.player == null ? null : mc.player.containerMenu;
		if (menu == null || mc.screen != sorting || menu.containerId != containerId) {
			sorting = null; // closed: the server gives back anything still held
			clicks.clear();
			return;
		}
		if (--wait > 0) {
			return;
		}
		if (!clicks.isEmpty()) {
			click(mc, menu, clicks.poll());
			wait = TICKS_PER_CLICK;
			return;
		}
		if (!menu.getCarried().isEmpty()) {
			// Waiting on the server, or something went wrong: never plan a swap while holding an item.
			if (++stuck > STUCK_TICKS) {
				stop(mc, "Sort stopped: an item is still on your cursor.");
			}
			return;
		}
		stuck = 0;
		if (!planSwap(menu)) {
			stop(mc, "Sorted!");
		} else if (++swaps > MAX_SWAPS) {
			clicks.clear();
			stop(mc, "Sort stopped: too many moves. The server may be undoing them.");
		}
	}

	private static void click(Minecraft mc, AbstractContainerMenu menu, int slot) {
		mc.gameMode.handleContainerInput(menu.containerId, slot, 0, ContainerInput.PICKUP, mc.player);
	}

	/** Queues the next swap from the live slots. False when everything is in order. */
	private boolean planSwap(AbstractContainerMenu menu) {
		List<Integer> free = new ArrayList<>();
		List<ItemStack> stacks = new ArrayList<>();
		for (int slot : slots) {
			ItemStack stack = menu.slots.get(slot).getItem();
			// Anything without a SkyBlock id (panes, buttons) stays where it is.
			if (stack.isEmpty() || !id(stack).isEmpty()) {
				free.add(slot);
				stacks.add(stack);
			}
		}
		List<ItemStack> order = stacks.stream().filter(s -> !s.isEmpty())
				.sorted(Comparator.comparingInt(InventorySorter::rarityRank).thenComparing(InventorySorter::id)).toList();
		for (int i = 0; i < order.size(); i++) {
			String want = id(order.get(i));
			ItemStack here = stacks.get(i);
			if (!here.isEmpty() && id(here).equals(want)) {
				continue;
			}
			for (int j = i + 1; j < stacks.size(); j++) {
				ItemStack there = stacks.get(j);
				if (!there.isEmpty() && id(there).equals(want)) {
					clicks.add(free.get(j));
					clicks.add(free.get(i));
					if (!here.isEmpty()) {
						clicks.add(free.get(j));
					}
					return true;
				}
			}
		}
		return false;
	}

	private void stop(Minecraft mc, String message) {
		sorting = null;
		boolean clean = clicks.isEmpty();
		clicks.clear();
		say(mc, message, clean && message.equals("Sorted!") ? ChatFormatting.GREEN : ChatFormatting.YELLOW);
	}

	private static void say(Minecraft mc, String message, ChatFormatting color) {
		if (mc.player != null) {
			mc.player.sendSystemMessage(Component.literal(message).withStyle(color));
		}
	}

	private static String id(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) {
			return "";
		}
		CompoundTag tag = data.copyTag();
		String id = tag.getStringOr("id", "");
		return id.isEmpty() ? tag.getCompoundOrEmpty("ExtraAttributes").getStringOr("id", "") : id;
	}

	/** Position in RARITIES, read from the lowest lore line that names one. Unknown sorts last. */
	private static int rarityRank(ItemStack stack) {
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore != null) {
			List<Component> lines = lore.lines();
			for (int i = lines.size() - 1; i >= 0; i--) {
				String line = lines.get(i).getString().replaceAll("§.", "");
				for (int r = 0; r < RARITIES.size(); r++) {
					if (Pattern.compile("^(?:\\S{1,2} )?" + RARITIES.get(r) + "\\b").matcher(line.trim()).find()) {
						return r;
					}
				}
			}
		}
		return RARITIES.size();
	}
}
