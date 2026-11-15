package dev.sw1ftz.progression;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;

import java.util.List;

/** TEMPORARY dev harness: fills in a sample profile and screenshots each new guide tab from the title screen. */
public final class DevShots {
	private static final List<String> PAGES = List.of("MUSEUM");
	private static int tick;
	private static int page = -1;

	public static void register() {
		if (System.getProperty("progression.shots") == null) {
			return;
		}
		ClientTickEvents.END_CLIENT_TICK.register(DevShots::onTick);
	}

	private static void onTick(Minecraft mc) {
		ProgressionClient client = ProgressionClient.get();
		if (page < 0) {
			if (!(mc.gui.screen() instanceof TitleScreen) || !client.database().ready() || !client.repo().loaded()) {
				return;
			}
			seed(client.profile());
			page = 0;
			tick = 0;
		}
		if (page >= PAGES.size()) {
			return;
		}
		tick++;
		if (tick == 1) {
			String name = PAGES.get(page);
			var guide = new dev.sw1ftz.progression.gui.ProgressionScreen(null);
			if (name.equals("MOVEHUD")) {
				mc.gui.setScreen(new dev.sw1ftz.progression.gui.MoveHudScreen(guide));
			} else {
				client.profile().lastCategory = name;
				mc.gui.setScreen(new dev.sw1ftz.progression.gui.ProgressionScreen(null));
			}
		} else if (tick == 240) {
			shot(mc, PAGES.get(page).toLowerCase());
		} else if (tick == 250) {
			page++;
			tick = 0;
			if (page == PAGES.size()) {
				ProgressionClient.LOGGER.info("SHOTS done");
			}
		}
	}

	private static void seed(PlayerProfile p) {
		p.skills.put("FARMING", 30);
		p.skills.put("MINING", 25);
		p.skills.put("COMBAT", 24);
		p.skills.put("FORAGING", 15);
		p.skills.put("FISHING", 18);
		p.catacombsLevel = 12;
		p.catacombsFloor = 3;
		p.slayers.put("zombie", 4);
		p.slayers.put("spider", 3);
		p.slayers.put("wolf", 2);
		p.slayerXp.put("zombie", 1800.0);
		p.slayerXp.put("spider", 420.0);
		p.slayerXp.put("wolf", 60.0);
		p.known.add("SLAYER");
		p.known.add("ACCESSORIES");
		p.accessories.addAll(List.of("ZOMBIE_TALISMAN", "SPEED_TALISMAN", "FARMING_TALISMAN", "VACCINE_TALISMAN", "WOLF_PAW"));
		p.ownedItems.add("FARM_SUIT_CHESTPLATE");
		p.pins.clear();
		p.pins.addAll(List.of("SKILL:FARMING", "SLAYER:zombie", "ITEM:FARM_ARMOR_CHESTPLATE"));
		p.purse = 150000;
		p.upgradeWay = "CRAFT";
		p.ownedItems.add("FARM_ARMOR_CHESTPLATE");
		for (String piece : List.of("HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS")) {
			p.ownedItems.add("FARM_ARMOR_" + piece);
		}
		p.ownedItems.add("DIVER_HELMET");
		p.museum.add("ABYSSAL");
		p.museum.add("FANCY_SWORD");
		p.known.add("MUSEUM");
		p.minions.clear();
		PlayerProfile.MinionState m = new PlayerProfile.MinionState();
		m.type = "COBBLESTONE";
		m.tier = 7;
		m.stored = 300;
		m.capacity = 960;
		m.seenAt = System.currentTimeMillis() - 3_600_000;
		p.minions.add(m);
		PlayerProfile.MinionState s = new PlayerProfile.MinionState();
		s.type = "SNOW";
		s.tier = 9;
		s.stored = 900;
		s.capacity = 1152;
		s.seenAt = System.currentTimeMillis() - 7_200_000;
		p.minions.add(s);
		ProgressionClient.get().profileChanged();
	}

	private static void shot(Minecraft mc, String name) {
		Screenshot.grab(mc.gameDirectory, "prog-" + name + ".png", mc.gameRenderer.mainRenderTarget(), 1,
				msg -> ProgressionClient.LOGGER.info("SHOTS {}", msg.getString()));
	}
}
