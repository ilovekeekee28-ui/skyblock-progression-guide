package dev.sw1ftz.progression;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Keeps your Garden copper current from the sidebar or tab list, on top of what the API sync reads. */
public final class CopperTracker {
	private static final Pattern COPPER = Pattern.compile("Copper:? *([\\d,]+)");
	private static int ticks;

	private CopperTracker() {
	}

	public static boolean inGarden(Minecraft mc) {
		String island = FairySouls.currentIsland(mc);
		return island != null && island.toLowerCase(Locale.ROOT).contains("garden");
	}

	public static void tick(Minecraft mc, PlayerProfile profile) {
		if (++ticks < 20 || mc.level == null || mc.getConnection() == null) {
			return;
		}
		ticks = 0;
		if (!inGarden(mc)) {
			return;
		}
		Scoreboard board = mc.level.getScoreboard();
		Objective sidebar = board.getDisplayObjective(DisplaySlot.SIDEBAR);
		if (sidebar != null) {
			for (PlayerScoreEntry entry : board.listPlayerScores(sidebar)) {
				PlayerTeam team = board.getPlayersTeam(entry.owner());
				String line = team == null ? entry.owner() : PlayerTeam.formatNameForTeam(team, entry.ownerName()).getString();
				if (read(line, profile)) {
					return;
				}
			}
		}
		for (PlayerInfo info : mc.getConnection().getListedOnlinePlayers()) {
			if (info.getTabListDisplayName() != null && read(info.getTabListDisplayName().getString(), profile)) {
				return;
			}
		}
	}

	private static boolean read(String line, PlayerProfile profile) {
		Matcher m = COPPER.matcher(line.replaceAll("§.", ""));
		if (!m.find()) {
			return false;
		}
		long copper = Long.parseLong(m.group(1).replace(",", ""));
		if (copper != profile.copper) {
			profile.copper = copper;
			profile.save();
		}
		return true;
	}
}
