package dev.sw1ftz.progression.gui;

import dev.sw1ftz.progression.Advisor;
import dev.sw1ftz.progression.BazaarPrices;
import dev.sw1ftz.progression.FairySouls;
import dev.sw1ftz.progression.GearItem;
import dev.sw1ftz.progression.Guide;
import dev.sw1ftz.progression.ItemDatabase;
import dev.sw1ftz.progression.PlayerProfile;
import dev.sw1ftz.progression.ProgressionClient;
import dev.sw1ftz.progression.Recommender;
import dev.sw1ftz.progression.Requirement;
import dev.sw1ftz.progression.SkillCategory;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.glfw.GLFW;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The guide: a tab per skill (best area, armor, weapon and tool you can use now, and what's next),
 * plus a Money tab with the best money-making methods you've unlocked.
 */
public class ProgressionScreen extends Screen {
	private static final int SIDEBAR_WIDTH = 104;
	private static final int ROW_HEIGHT = 16;
	private static final int MONEY_ROW_HEIGHT = 24;
	/** Cards shorter than this use the one-row layout. */
	private static final int COMPACT_HEIGHT = 42;
	/** Pages after the skill tabs, in sidebar order. */
	private static final List<String> EXTRA_PAGES = List.of("MONEY", "SOULS", "PROGRESS", "ACCESSORIES", "UPGRADES", "MUSEUM", "MINIONS");
	private static final List<String> EXTRA_TITLES = List.of("Money", "Fairy Souls", "Progress", "Accessories", "Upgrades", "Museum", "Minions");
	private static final int TAB_COUNT = SkillCategory.values().length + EXTRA_PAGES.size();
	private static final int MONEY_TAB = SkillCategory.values().length;
	private static final int LIST_ROW_HEIGHT = 24;
	private static final int SECTION_ROW_HEIGHT = 14;
	private static final int SOUL_ROW_HEIGHT = 13;
	private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};

	private final Screen parent;
	private final PlayerProfile profile = ProgressionClient.get().profile();
	private final ItemDatabase database = ProgressionClient.get().database();
	private final Guide guide = ProgressionClient.get().guide();
	private final BazaarPrices prices = ProgressionClient.get().prices();

	/** The selected skill, or null when the Money or Fairy Souls tab is selected. */
	private SkillCategory selected;
	/** "MONEY" or "SOULS" when {@code selected} is null. */
	private String extraPage = "MONEY";
	/** Island shown on the Fairy Souls tab. */
	private String soulIsland;
	/** Where the island switcher's arrows were last drawn, for clicks. */
	private int switcherLeft, switcherRight, switcherArrow;
	private int left, top, panelWidth, panelHeight;
	private int contentLeft, contentRight, cardsTop, cardsBottom;
	private double moneyScroll;

	/** Clickable areas drawn this frame that open an item's recipe. */
	private record RecipeTarget(int x0, int y0, int x1, int y1, String id) {
	}

	private final List<RecipeTarget> recipeTargets = new ArrayList<>();
	/** Rows drawn this frame that shift+click pins to the HUD; {@code id} is the pin key. */
	private final List<RecipeTarget> pinTargets = new ArrayList<>();

	/** The list's scrollbar as last drawn, for dragging it. */
	private int barTop, barBottom, barThumb;
	private double barMax;
	private boolean draggingBar;
	/** Where the skill header's [Pin] and [Move HUD] were last drawn. */
	private int pinLeft = -1, pinRight, moveLeft, moveRight;

	private List<Recommender.Pick> picks = List.of();
	private Object cachedFor;

	public ProgressionScreen(Screen parent) {
		super(Component.literal("SkyBlock Progression"));
		this.parent = parent;
		if (EXTRA_PAGES.contains(profile.lastCategory)) {
			extraPage = profile.lastCategory;
		} else {
			try {
				selected = SkillCategory.valueOf(profile.lastCategory);
			} catch (IllegalArgumentException e) {
				selected = SkillCategory.FARMING;
			}
		}
		String here = FairySouls.currentIsland(net.minecraft.client.Minecraft.getInstance());
		soulIsland = here != null ? here : "hub";
	}

	@Override
	protected void init() {
		panelWidth = Math.min(480, width - 16);
		panelHeight = Math.min(270, height - 16);
		left = (width - panelWidth) / 2;
		top = (height - panelHeight) / 2;
		contentLeft = left + 8 + SIDEBAR_WIDTH + 8;
		contentRight = left + panelWidth - 8;
		cardsTop = top + Theme.HEADER_HEIGHT + 24;
		cardsBottom = top + panelHeight - 34;

		int buttonY = top + panelHeight - 28;
		int gap = 6;
		int buttonWidth = Math.min(86, (panelWidth - 16 - SIDEBAR_WIDTH - 8 - gap * 3) / 4);
		int x = left + panelWidth - 8 - buttonWidth * 4 - gap * 3;
		addRenderableWidget(Button.builder(Component.literal(profile.apiKey.isBlank() ? "Add API Key" : "API Key"),
						b -> minecraft.gui.setScreen(new ApiKeyScreen(this)))
				.bounds(x, buttonY, buttonWidth, 20)
				.tooltip(Tooltip.create(Component.literal("Add your Hypixel API key so your stats load by themselves.")))
				.build());
		x += buttonWidth + gap;
		addRenderableWidget(Button.builder(Component.literal("My Levels"), b -> minecraft.gui.setScreen(new LevelsScreen(this)))
				.bounds(x, buttonY, buttonWidth, 20)
				.tooltip(Tooltip.create(Component.literal("Set your skill, Catacombs, HOTM and Garden levels.\n"
						+ "Tip: opening /skills in SkyBlock fills your skills in automatically.")))
				.build());
		x += buttonWidth + gap;
		addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> {
					database.load(true);
					prices.refreshIfOld();
				})
				.bounds(x, buttonY, buttonWidth, 20)
				.tooltip(Tooltip.create(Component.literal("Download the latest item list and bazaar prices from Hypixel.\n"
						+ "(Items also refresh by themselves once a day.)")))
				.build());
		x += buttonWidth + gap;
		addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
				.bounds(x, buttonY, buttonWidth, 20)
				.build());

		searchBox = new EditBox(font, left + 8, buttonY, SIDEBAR_WIDTH, 20, Component.literal("Search"));
		searchBox.setHint(Component.literal("Search this tab"));
		searchBox.setValue(search);
		searchBox.setResponder(text -> {
			search = text;
			moneyScroll = 0;
		});
		addRenderableWidget(searchBox);

		prices.refreshIfOld();
	}

	private List<Recommender.Pick> picks() {
		Object key = List.of(selected, database.items(), ProgressionClient.get().profileVersion());
		if (!key.equals(cachedFor)) {
			picks = database.ready() ? Recommender.recommend(selected, database.items(), profile) : List.of();
			cachedFor = key;
		}
		return picks;
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(graphics, mouseX, mouseY, partialTick);
		Theme.panel(graphics, left, top, panelWidth, panelHeight);
		Theme.inset(graphics, left + 8, top + Theme.HEADER_HEIGHT + 8, SIDEBAR_WIDTH, panelHeight - Theme.HEADER_HEIGHT - 42);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		searchBox.setVisible(selected == null && !extraPage.equals("MONEY") && !extraPage.equals("SOULS"));
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		recipeTargets.clear();
		pinTargets.clear();
		barMax = 0;

		graphics.text(font, "SkyBlock Progression", left + 10, top + 8, Theme.ACCENT, true);
		boolean noLevels = profile.skills.isEmpty();
		String status = noLevels ? "Open /skills to load your levels" : database.status();
		graphics.text(font, status, left + panelWidth - 10 - font.width(status), top + 8, noLevels ? Theme.COINS : Theme.MUTED, true);

		extractSidebar(graphics, mouseX, mouseY);
		if (selected != null) {
			extractSkill(graphics, mouseX, mouseY);
		} else if (extraPage.equals("SOULS")) {
			extractSouls(graphics, mouseX, mouseY);
		} else if (!extraPage.equals("MONEY")) {
			extractInfoPage(graphics, mouseX, mouseY);
		} else {
			extractMoney(graphics, mouseX, mouseY);
		}
	}

	// ---------------------------------------------------------------- sidebar

	/** Sidebar rows shrink a little when there are too many tabs for the panel. */
	private int rowHeight() {
		int space = panelHeight - Theme.HEADER_HEIGHT - 42 - 12 - 6;
		return Math.max(10, Math.min(ROW_HEIGHT, space / TAB_COUNT));
	}

	private void extractSidebar(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int x = left + 8;
		int y = top + Theme.HEADER_HEIGHT + 11;
		int rowHeight = rowHeight();
		int textY = (rowHeight - 8) / 2;
		for (int i = 0; i < TAB_COUNT; i++) {
			boolean extra = i >= MONEY_TAB;
			SkillCategory skill = extra ? null : SkillCategory.values()[i];
			String page = extra ? EXTRA_PAGES.get(i - MONEY_TAB) : null;
			boolean isSelected = extra ? selected == null && extraPage.equals(page) : selected == skill;
			if (i == MONEY_TAB) {
				// a thin divider between the skills and the extra tabs
				graphics.fill(x + 6, y + 2, x + SIDEBAR_WIDTH - 6, y + 3, Theme.BORDER);
				y += 6;
			}
			boolean hovered = mouseX >= x && mouseX < x + SIDEBAR_WIDTH && mouseY >= y && mouseY < y + rowHeight;
			if (isSelected) {
				graphics.fill(x + 1, y, x + SIDEBAR_WIDTH - 1, y + rowHeight, 0x503296DC);
				graphics.fill(x + 1, y, x + 3, y + rowHeight, Theme.HIGHLIGHT);
			} else if (hovered) {
				graphics.fill(x + 1, y, x + SIDEBAR_WIDTH - 1, y + rowHeight, 0x20FFFFFF);
			}
			String title;
			String right;
			int titleColor;
			if (extra) {
				title = EXTRA_TITLES.get(i - MONEY_TAB);
				right = switch (page) {
					case "MONEY" -> "$";
					case "SOULS" -> String.valueOf(profile.foundSouls.size());
					case "MINIONS" -> String.valueOf(profile.minions.size());
					case "MUSEUM" -> profile.museum.isEmpty() ? "" : String.valueOf(profile.museum.size());
					case "ACCESSORIES" -> profile.accessories.isEmpty() ? "" : String.valueOf(profile.accessories.size());
					default -> "";
				};
				int base = switch (page) {
					case "MONEY" -> Theme.COINS;
					case "SOULS" -> 0xFFFF55FF;
					default -> 0xFF55FFFF;
				};
				titleColor = isSelected ? base : (base & 0x00FFFFFF) | 0xAA000000;
			} else {
				title = skill.title;
				right = String.valueOf(skill.level(profile));
				titleColor = isSelected ? Theme.TEXT : Theme.MUTED;
			}
			graphics.text(font, title, x + 8, y + textY, titleColor, false);
			graphics.text(font, right, x + SIDEBAR_WIDTH - 8 - font.width(right), y + textY, isSelected ? Theme.ACCENT : 0xFF777777, false);
			y += rowHeight;
		}
	}

	private int tabAt(double mouseX, double mouseY) {
		int x = left + 8;
		int y = top + Theme.HEADER_HEIGHT + 11;
		int rowHeight = rowHeight();
		if (mouseX < x || mouseX >= x + SIDEBAR_WIDTH) {
			return -1;
		}
		for (int i = 0; i < TAB_COUNT; i++) {
			if (i == MONEY_TAB) {
				y += 6;
			}
			if (mouseY >= y && mouseY < y + rowHeight) {
				return i;
			}
			y += rowHeight;
		}
		return -1;
	}

	// ---------------------------------------------------------------- progress, accessories, upgrades, minions

	private List<InfoPages.Row> infoRows = List.of();
	private Object infoKey;
	private long infoBuiltAt;
	private boolean sortByImpact;
	/** Where the header action ("Scan AH", "Sort", ...) was last drawn; -1 when there is none. */
	private int actionLeft = -1, actionRight;
	/** Where the Upgrades tab's [Get: ...] filter was last drawn; -1 when there is none. */
	private int wayLeft = -1, wayRight;
	/** Where the Upgrades tab's purse filter was last drawn; -1 when there is none. */
	private int affordLeft = -1, affordRight;
	private EditBox searchBox;
	private String search = "";

	/** The rows matching the search box; headings are dropped while searching. */
	private List<InfoPages.Row> filterRows(List<InfoPages.Row> rows) {
		String query = search.trim().toLowerCase(Locale.ROOT);
		if (query.isEmpty()) {
			return rows;
		}
		List<InfoPages.Row> out = new ArrayList<>();
		for (InfoPages.Row row : rows) {
			if (!row.section() && (row.title() + " " + row.sub()).toLowerCase(Locale.ROOT).contains(query)) {
				out.add(row);
			}
		}
		if (out.isEmpty()) {
			out.add(InfoPages.Row.hint("Nothing matches \"" + search.trim() + "\"."));
		}
		return out;
	}

	private List<InfoPages.Row> infoRows() {
		Object key = List.of(extraPage, ProgressionClient.get().profileVersion(), sortByImpact, profile.upgradeWay, profile.onlyAffordable, profile.museumSetsOnly, profile.accessoriesByMp, search, ProgressionClient.get().repo().museumEntries().size(), database.items().size());
		long now = System.currentTimeMillis();
		// Rebuilt every few seconds too, so prices and "full in" times stay fresh.
		if (!key.equals(infoKey) || now - infoBuiltAt > 3000) {
			infoRows = filterRows(switch (extraPage) {
				case "PROGRESS" -> InfoPages.progress(profile);
				case "ACCESSORIES" -> InfoPages.accessories(profile);
				case "UPGRADES" -> InfoPages.upgrades(profile, sortByImpact);
				case "MUSEUM" -> InfoPages.museum(profile);
				default -> InfoPages.minions(profile);
			});
			infoKey = key;
			infoBuiltAt = now;
		}
		return infoRows;
	}

	private String headerTitle() {
		return switch (extraPage) {
			case "PROGRESS" -> "Progress";
			case "ACCESSORIES" -> "Accessories  " + Advisor.totalMagicalPower(profile) + " MP";
			case "UPGRADES" -> "Upgrades";
			case "MUSEUM" -> "Museum  " + Advisor.museumDonated(profile).size() + "/" + ProgressionClient.get().repo().museumEntries().size() + " donated";
			default -> "Minions";
		};
	}

	private String headerAction() {
		return switch (extraPage) {
			case "PROGRESS" -> profile.apiKey.isBlank() ? "" : "[Sync API]";
			case "ACCESSORIES" -> ProgressionClient.get().auctions().scanning() ? "Scanning AH..." : "[Scan AH]";
			case "UPGRADES" -> "[" + (sortByImpact ? "Impact" : "Cheapest") + "]";
			case "MUSEUM" -> "[Show: " + (profile.museumSetsOnly ? "Sets" : "Everything") + "]";
			default -> "[Alerts: " + (profile.minionAlerts ? "ON" : "OFF") + "]";
		};
	}

	private void runHeaderAction() {
		switch (extraPage) {
			case "PROGRESS" -> ProgressionClient.get().api().sync(profile);
			case "ACCESSORIES" -> ProgressionClient.get().auctions().scan();
			case "UPGRADES" -> sortByImpact = !sortByImpact;
			case "MUSEUM" -> {
				profile.museumSetsOnly = !profile.museumSetsOnly;
				profile.save();
				moneyScroll = 0;
			}
			default -> {
				profile.minionAlerts = !profile.minionAlerts;
				profile.save();
			}
		}
		infoKey = null;
	}

	private List<FormattedCharSequence> headerActionTooltip() {
		String text = switch (extraPage) {
			case "PROGRESS" -> "Re-read your stats from the Hypixel API now.";
			case "ACCESSORIES" -> "Download every Auction House page to get lowest BIN prices (about 30 seconds). "
					+ ProgressionClient.get().auctions().status();
			case "MUSEUM" -> "Sets: armor sets only. Everything: single items and weapons too.";
			case "UPGRADES" -> "Cheapest: lowest cost first. Impact: biggest stat gain over what you own first.";
			default -> "Chat alert when a minion is estimated to be full.";
		};
		return font.split(Component.literal(text), 220);
	}

	private void extractInfoPage(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int headerY = top + Theme.HEADER_HEIGHT + 9;
		String title = headerTitle();
		graphics.text(font, title, contentLeft, headerY, 0xFF55FFFF, true);
		String action = headerAction();
		if (!action.isEmpty()) {
			actionRight = contentRight;
			actionLeft = contentRight - font.width(action);
			boolean over = mouseX >= actionLeft && mouseX < actionRight && mouseY >= headerY - 3 && mouseY < headerY + 11;
			graphics.text(font, action, actionLeft, headerY, over ? Theme.HIGHLIGHT : Theme.ACCENT, false);
			if (over) {
				graphics.setTooltipForNextFrame(font, headerActionTooltip(), mouseX, mouseY);
			}
		} else {
			actionLeft = -1;
		}
		wayLeft = -1;
		if (extraPage.equals("ACCESSORIES")) {
			String sort = "[Sort: " + (profile.accessoriesByMp ? "Most MP" : "Cheapest per MP") + "]";
			wayRight = actionLeft - 6;
			wayLeft = wayRight - font.width(sort);
			boolean over = mouseX >= wayLeft && mouseX < wayRight && mouseY >= headerY - 3 && mouseY < headerY + 11;
			graphics.text(font, sort, wayLeft, headerY, over ? Theme.HIGHLIGHT : 0xFF55FFFF, false);
			if (over) {
				graphics.setTooltipForNextFrame(font, font.split(Component.literal("Cheapest per MP: best value first. "
						+ "Most MP: biggest Magical Power gain first. Click to switch."), 220), mouseX, mouseY);
			}
		}
		if (extraPage.equals("UPGRADES")) {
			String way = "[Get: " + InfoPages.wayLabel(profile.upgradeWay) + "]";
			wayRight = actionLeft - 6;
			wayLeft = wayRight - font.width(way);
			boolean over = mouseX >= wayLeft && mouseX < wayRight && mouseY >= headerY - 3 && mouseY < headerY + 11;
			graphics.text(font, way, wayLeft, headerY, over ? Theme.HIGHLIGHT : 0xFF55FFFF, false);
			if (over) {
				graphics.setTooltipForNextFrame(font, font.split(Component.literal("Cheapest way: buy or craft, whichever costs less. "
						+ "Craft/Upgrade: only things you can craft or upgrade into, counting gear you already own as part of the recipe. "
						+ "Buy: buying prices only. Click to switch."), 220), mouseX, mouseY);
			}
			affordLeft = -1;
			if (profile.purse > 0) {
				String afford = profile.onlyAffordable ? "[In purse]" : "[Any price]";
				affordRight = wayLeft - 6;
				affordLeft = affordRight - font.width(afford);
				boolean overAfford = mouseX >= affordLeft && mouseX < affordRight && mouseY >= headerY - 3 && mouseY < headerY + 11;
				graphics.text(font, afford, affordLeft, headerY, overAfford ? Theme.HIGHLIGHT : profile.onlyAffordable ? Theme.GOOD : Theme.MUTED, false);
				if (overAfford) {
					graphics.setTooltipForNextFrame(font, font.split(Component.literal("In purse: only show what the "
							+ coins(profile.purse) + " in your purse can pay for now. Click to switch."), 220), mouseX, mouseY);
				}
			}
		}
		String status = extraPage.equals("PROGRESS") ? ProgressionClient.get().api().status() : "";
		if (!status.isEmpty()) {
			int statusX = contentLeft + font.width(title) + 8;
			drawTrimmed(graphics, Component.literal(status).withStyle(color(Theme.MUTED)), statusX, headerY,
					(actionLeft < 0 ? contentRight : actionLeft - 8) - statusX);
		}

		List<InfoPages.Row> rows = infoRows();
		int listTop = moneyListTop();
		int listBottom = cardsBottom;
		Theme.inset(graphics, contentLeft, listTop, contentRight - contentLeft, listBottom - listTop);
		int contentHeight = 4;
		for (InfoPages.Row row : rows) {
			contentHeight += row.section() ? SECTION_ROW_HEIGHT : LIST_ROW_HEIGHT;
		}
		double maxScroll = Math.max(0, contentHeight - (listBottom - listTop));
		moneyScroll = Mth.clamp(moneyScroll, 0, maxScroll);

		graphics.enableScissor(contentLeft + 1, listTop + 1, contentRight - 1, listBottom - 1);
		int y = listTop + 3 - (int) moneyScroll;
		int x = contentLeft + 6;
		int right = contentRight - 8;
		for (InfoPages.Row row : rows) {
			int h = row.section() ? SECTION_ROW_HEIGHT : LIST_ROW_HEIGHT;
			if (y + h > listTop && y < listBottom) {
				if (row.section()) {
					drawTrimmed(graphics, Component.literal(row.title()).withStyle(color(row.titleColor())), x, y + 3, right - x);
				} else {
					boolean hovered = mouseX >= contentLeft && mouseX < contentRight && mouseY >= Math.max(y, listTop) && mouseY < Math.min(y + h, listBottom);
					if (hovered) {
						graphics.fill(contentLeft + 1, y, contentRight - 1, y + h, 0x20FFFFFF);
						if (row.tooltip() != null) {
							graphics.setTooltipForNextFrame(font, row.tooltip().stream().map(Component::getVisualOrderText).toList(), mouseX, mouseY);
						}
					}
					if (row.recipeId() != null) {
						recipeTargets.add(new RecipeTarget(contentLeft, Math.max(y, listTop), contentRight, Math.min(y + h, listBottom), row.recipeId()));
					}
					if (row.pin() != null) {
						pinTargets.add(new RecipeTarget(contentLeft, Math.max(y, listTop), contentRight, Math.min(y + h, listBottom), row.pin()));
					}
					int rightWidth = row.right().isEmpty() ? 0 : font.width(row.right()) + 8;
					drawTrimmed(graphics, Component.literal(row.title()).withStyle(color(row.titleColor())), x, y + 3, right - rightWidth - x);
					if (!row.right().isEmpty()) {
						graphics.text(font, row.right(), right - font.width(row.right()), y + 3, row.rightColor(), false);
					}
					if (!row.sub().isEmpty()) {
						drawTrimmed(graphics, Component.literal(row.sub()).withStyle(color(row.subColor())), x, y + 13, right - x);
					}
				}
			}
			y += h;
		}
		graphics.disableScissor();
		extractScrollbar(graphics, listTop, listBottom, contentHeight, maxScroll);
	}

	/** Draws the list's scrollbar and remembers it so it can be dragged. */
	private void extractScrollbar(GuiGraphicsExtractor graphics, int listTop, int listBottom, int contentHeight, double maxScroll) {
		if (maxScroll <= 0) {
			return;
		}
		int trackHeight = listBottom - listTop - 4;
		int barHeight = Math.max(14, trackHeight * trackHeight / Math.max(1, contentHeight));
		int barY = listTop + 2 + (int) ((trackHeight - barHeight) * (moneyScroll / maxScroll));
		graphics.fill(contentRight - 5, listTop + 2, contentRight - 1, listBottom - 2, 0x30FFFFFF);
		graphics.fill(contentRight - 5, barY, contentRight - 1, barY + barHeight, Theme.HIGHLIGHT);
		barTop = listTop + 2;
		barBottom = listBottom - 2;
		barThumb = barHeight;
		barMax = maxScroll;
	}

	private boolean scrollbarClicked(double mouseX, double mouseY) {
		if (barMax <= 0 || mouseX < contentRight - 8 || mouseX >= contentRight || mouseY < barTop || mouseY >= barBottom) {
			return false;
		}
		draggingBar = true;
		dragScrollTo(mouseY);
		return true;
	}

	private void dragScrollTo(double mouseY) {
		double range = barBottom - barTop - barThumb;
		if (range > 0) {
			moneyScroll = Mth.clamp((mouseY - barTop - barThumb / 2.0) / range, 0, 1) * barMax;
		}
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (draggingBar) {
			dragScrollTo(event.y());
			return true;
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		draggingBar = false;
		return super.mouseReleased(event);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (selected == null && !searchBox.isFocused()) {
			int page = cardsBottom - moneyListTop() - 20;
			double step = switch (event.key()) {
				case GLFW.GLFW_KEY_UP -> -MONEY_ROW_HEIGHT;
				case GLFW.GLFW_KEY_DOWN -> MONEY_ROW_HEIGHT;
				case GLFW.GLFW_KEY_PAGE_UP -> -page;
				case GLFW.GLFW_KEY_PAGE_DOWN -> page;
				case GLFW.GLFW_KEY_HOME -> -1e9;
				case GLFW.GLFW_KEY_END -> 1e9;
				default -> 0;
			};
			if (step != 0) {
				moneyScroll = Math.max(0, moneyScroll + step);
				return true;
			}
		}
		return super.keyPressed(event);
	}

	/** Shift+click: pin or unpin the row or item under the mouse. */
	private boolean pinClicked(double mouseX, double mouseY) {
		List<RecipeTarget> targets = new ArrayList<>(pinTargets);
		for (RecipeTarget t : recipeTargets) {
			targets.add(new RecipeTarget(t.x0(), t.y0(), t.x1(), t.y1(), "ITEM:" + t.id()));
		}
		for (RecipeTarget t : targets) {
			if (mouseX >= t.x0() && mouseX < t.x1() && mouseY >= t.y0() && mouseY < t.y1()) {
				pinChanged(profile.togglePin(t.id()));
				return true;
			}
		}
		return false;
	}

	private void pinChanged(boolean pinned) {
		infoKey = null;
		if (minecraft.player != null) {
			minecraft.player.sendSystemMessage(Component.literal(pinned
					? "Pinned to your HUD. Use [Move HUD] on a skill tab to move it."
					: "Unpinned from your HUD.").withStyle(pinned ? ChatFormatting.GREEN : ChatFormatting.GRAY));
		}
	}

	private boolean skillHeaderClicked(double mouseX, double mouseY) {
		int headerY = top + Theme.HEADER_HEIGHT + 9;
		if (pinLeft < 0 || mouseY < headerY - 3 || mouseY >= headerY + 11) {
			return false;
		}
		if (mouseX >= pinLeft && mouseX < pinRight) {
			pinChanged(profile.togglePin("SKILL:" + selected.name()));
			return true;
		}
		if (mouseX >= moveLeft && mouseX < moveRight) {
			minecraft.gui.setScreen(new MoveHudScreen(this));
			return true;
		}
		return false;
	}

	/** Left-click on the header action runs it; right-click on a row runs its action (e.g. forget a minion). */
	private boolean infoClicked(double mouseX, double mouseY, int button) {
		int headerY = top + Theme.HEADER_HEIGHT + 9;
		if (button == 0 && actionLeft >= 0 && mouseX >= actionLeft && mouseX < actionRight && mouseY >= headerY - 3 && mouseY < headerY + 11) {
			runHeaderAction();
			return true;
		}
		if (button == 0 && wayLeft >= 0 && mouseX >= wayLeft && mouseX < wayRight && mouseY >= headerY - 3 && mouseY < headerY + 11) {
			if (extraPage.equals("ACCESSORIES")) {
				profile.accessoriesByMp = !profile.accessoriesByMp;
				profile.save();
				infoKey = null;
				moneyScroll = 0;
				return true;
			}
			profile.upgradeWay = switch (profile.upgradeWay) {
				case "BEST" -> "CRAFT";
				case "CRAFT" -> "BUY";
				default -> "BEST";
			};
			profile.save();
			infoKey = null;
			moneyScroll = 0;
			return true;
		}
		if (button == 0 && extraPage.equals("UPGRADES") && affordLeft >= 0 && mouseX >= affordLeft && mouseX < affordRight && mouseY >= headerY - 3 && mouseY < headerY + 11) {
			profile.onlyAffordable = !profile.onlyAffordable;
			profile.save();
			infoKey = null;
			moneyScroll = 0;
			return true;
		}
		int listTop = moneyListTop();
		if (button != 1 || mouseX < contentLeft || mouseX >= contentRight || mouseY < listTop || mouseY >= cardsBottom) {
			return false;
		}
		int y = listTop + 3 - (int) moneyScroll;
		for (InfoPages.Row row : infoRows()) {
			int h = row.section() ? SECTION_ROW_HEIGHT : LIST_ROW_HEIGHT;
			if (mouseY >= y && mouseY < y + h) {
				if (row.click() == null) {
					return false;
				}
				row.click().run();
				infoKey = null;
				return true;
			}
			y += h;
		}
		return false;
	}

	// ---------------------------------------------------------------- skill pages

	private void extractSkill(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int level = selected.level(profile);
		String levelText = selected == SkillCategory.DUNGEONS ? "  Catacombs " + level : "  Level " + level;
		graphics.text(font, selected.title, contentLeft, top + Theme.HEADER_HEIGHT + 9, Theme.ACCENT, true);
		graphics.text(font, levelText, contentLeft + font.width(selected.title), top + Theme.HEADER_HEIGHT + 9, Theme.MUTED, true);
		int headerY = top + Theme.HEADER_HEIGHT + 9;
		boolean pinned = profile.pins.contains("SKILL:" + selected.name());
		String pin = pinned ? "[★ Pinned]" : "[Pin]";
		String move = "[Move HUD]";
		moveRight = contentRight;
		moveLeft = moveRight - font.width(move);
		pinRight = moveLeft - 6;
		pinLeft = pinRight - font.width(pin);
		boolean overPin = mouseY >= headerY - 3 && mouseY < headerY + 11 && mouseX >= pinLeft && mouseX < pinRight;
		boolean overMove = mouseY >= headerY - 3 && mouseY < headerY + 11 && mouseX >= moveLeft && mouseX < moveRight;
		graphics.text(font, pin, pinLeft, headerY, overPin ? Theme.HIGHLIGHT : pinned ? Theme.GOOD : Theme.ACCENT, false);
		graphics.text(font, move, moveLeft, headerY, overMove ? Theme.HIGHLIGHT : Theme.MUTED, false);
		if (overPin) {
			graphics.setTooltipForNextFrame(font, font.split(Component.literal(
					"Show this skill and what it needs next on your floating HUD. Shift+click any item to pin it too."), 220), mouseX, mouseY);
		}

		if (!database.ready()) {
			graphics.text(font, database.status(), contentLeft, cardsTop + 4, Theme.MUTED, false);
			return;
		}

		List<Recommender.Pick> picks = picks();
		int cards = picks.size() + 1;
		int gap = 5;
		int cardHeight = (cardsBottom - cardsTop - gap * (cards - 1)) / cards;
		int y = cardsTop;
		extractAreaCard(graphics, guide.area(selected, profile), y, cardHeight, mouseX, mouseY);
		y += cardHeight + gap;
		for (Recommender.Pick pick : picks) {
			extractGearCard(graphics, pick, y, cardHeight, mouseX, mouseY);
			y += cardHeight + gap;
		}
	}

	private void extractAreaCard(GuiGraphicsExtractor graphics, Guide.Progress area, int y, int height, int mouseX, int mouseY) {
		int x = contentLeft;
		int width = contentRight - contentLeft;
		Theme.inset(graphics, x, y, width, height);
		int textX = x + 6;
		int maxWidth = width - 12;
		graphics.text(font, "BEST AREA", textX, y + 5, Theme.HIGHLIGHT, false);

		// Short cards (small screens, or 4 cards on a tab) put the first line next to the label.
		boolean compact = height < COMPACT_HEIGHT;
		int lineX = compact ? textX + font.width("BEST AREA") + 8 : textX;
		int lineY = compact ? y + 5 : y + 16;
		int lineWidth = maxWidth - (lineX - textX);
		if (area.best() == null) {
			graphics.text(font, "No area data for this skill.", lineX, lineY, Theme.MUTED, false);
			return;
		}
		Guide.Entry best = area.best();
		MutableComponent line = Component.literal(best.name()).withStyle(color(Theme.TEXT))
				.append(Component.literal("  " + best.info()).withStyle(color(Theme.MUTED)));
		drawTrimmed(graphics, line, lineX, lineY, lineWidth);
		if (hovered(mouseX, mouseY, lineX, lineY, lineWidth)) {
			graphics.setTooltipForNextFrame(font, entryDetails(best, true), mouseX, mouseY);
		}
		extractNextEntry(graphics, area.next(), textX, y + height - 13, maxWidth, mouseX, mouseY, "You're in the best area for this skill!");
	}

	private void extractNextEntry(GuiGraphicsExtractor graphics, Guide.Entry next, int x, int y, int maxWidth, int mouseX, int mouseY,
								  String doneText) {
		if (next == null) {
			graphics.text(font, doneText, x, y, Theme.GOOD, false);
			return;
		}
		MutableComponent line = Component.literal("Next: ").withStyle(color(Theme.MUTED))
				.append(Component.literal(next.name()).withStyle(color(Theme.COINS)))
				.append(Component.literal("  needs " + String.join(", ", next.missing(profile))).withStyle(color(Theme.BAD)));
		drawTrimmed(graphics, line, x, y, maxWidth);
		if (hovered(mouseX, mouseY, x, y, maxWidth)) {
			graphics.setTooltipForNextFrame(font, entryDetails(next, false), mouseX, mouseY);
		}
	}

	private void extractGearCard(GuiGraphicsExtractor graphics, Recommender.Pick pick, int y, int height, int mouseX, int mouseY) {
		int x = contentLeft;
		int width = contentRight - contentLeft;
		Theme.inset(graphics, x, y, width, height);
		int textX = x + 6;
		int maxWidth = width - 12;
		Map<String, Double> weights = pick.slot().isArmor() ? selected.armorWeights : selected.toolWeights;

		String label = pick.slot().label().toUpperCase(Locale.ROOT);
		graphics.text(font, label, textX, y + 5, Theme.HIGHLIGHT, false);
		boolean compact = height < COMPACT_HEIGHT;
		if (!compact) {
			String count = pick.choices() + " options";
			graphics.text(font, count, x + width - 6 - font.width(count), y + 5, 0xFF666666, false);
		}

		// Short cards put "Best now" on the label's row.
		int lineX = compact ? textX + font.width(label) + 8 : textX;
		int lineY = compact ? y + 5 : y + 16;
		Recommender.Option best = pick.best();
		boolean roomy = height >= 50;
		if (best == null) {
			graphics.text(font, "Nothing unlocked yet. Level up to unlock your first one.", lineX, lineY, Theme.MUTED, false);
		} else {
			textX = lineX;
			maxWidth = width - 12 - (lineX - (x + 6));
			String stage = best.stageName();
			MutableComponent line = Component.literal("Best now: ").withStyle(color(Theme.MUTED))
					.append(Component.literal(best.name()).withStyle(color(rarityColor(best.rarity()))));
			if (!pick.similar().isEmpty()) {
				line.append(Component.literal("  +" + pick.similar().size() + " similar").withStyle(color(Theme.MUTED)));
			}
			if (!roomy) {
				String stats = Recommender.keyStats(best, weights);
				if (!stats.isEmpty()) {
					line.append(Component.literal("  " + stats).withStyle(color(Theme.GOOD)));
				}
			}
			drawTrimmed(graphics, line, textX, lineY, maxWidth - font.width(stage) - 8);
			graphics.text(font, stage, x + width - 6 - font.width(stage), lineY, Theme.MUTED, false);
			if (hovered(mouseX, mouseY, textX, lineY, maxWidth)) {
				graphics.setTooltipForNextFrame(font, details(best, weights, pick.similar()), mouseX, mouseY);
			}
			recipeTargets.add(new RecipeTarget(textX, lineY - 2, textX + maxWidth, lineY + font.lineHeight + 1, Advisor.recipeId(best)));
			if (roomy) {
				String stats = Recommender.keyStats(best, weights);
				if (stats.isEmpty()) {
					stats = best.pieces().size() > 1 ? best.pieces().size() + "-piece set" : "Upgrades as you use it";
				}
				drawTrimmed(graphics, Component.literal(stats).withStyle(color(Theme.GOOD)), textX, lineY + 11, maxWidth);
			}
		}

		int nextX = x + 6;
		int nextWidth = width - 12;
		int nextY = y + height - 13;
		Recommender.Option next = pick.next();
		if (next == null) {
			graphics.text(font, best == null ? "" : "You already have the best one!", nextX, nextY, Theme.GOOD, false);
		} else {
			MutableComponent line = Component.literal("Next: ").withStyle(color(Theme.MUTED))
					.append(Component.literal(next.name()).withStyle(color(rarityColor(next.rarity()))))
					.append(Component.literal("  needs " + String.join(", ", next.missing(profile))).withStyle(color(Theme.BAD)));
			drawTrimmed(graphics, line, nextX, nextY, nextWidth);
			if (hovered(mouseX, mouseY, nextX, nextY, nextWidth)) {
				graphics.setTooltipForNextFrame(font, details(next, weights, null), mouseX, mouseY);
			}
			recipeTargets.add(new RecipeTarget(nextX, nextY - 2, nextX + nextWidth, nextY + font.lineHeight + 1, Advisor.recipeId(next)));
		}
	}

	// ---------------------------------------------------------------- money page

	/** Unlocked methods best-first, then the locked ones in the order you'll reach them. */
	private List<Guide.Entry> moneyRows() {
		List<Guide.Entry> unlocked = new ArrayList<>();
		List<Guide.Entry> locked = new ArrayList<>();
		for (Guide.Entry e : guide.money()) {
			(e.unlocked(profile) ? unlocked : locked).add(e);
		}
		List<Guide.Entry> rows = new ArrayList<>(unlocked.reversed());
		rows.addAll(locked);
		return rows;
	}

	private int moneyListTop() {
		return top + Theme.HEADER_HEIGHT + 24;
	}

	private void extractMoney(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		graphics.text(font, "Money Making", contentLeft, top + Theme.HEADER_HEIGHT + 9, Theme.ACCENT, true);
		String hint = prices.loaded() ? "Live bazaar sell prices" : "Loading bazaar prices...";
		graphics.text(font, hint, contentRight - font.width(hint), top + Theme.HEADER_HEIGHT + 9, Theme.MUTED, false);

		List<Guide.Entry> rows = moneyRows();
		int listTop = moneyListTop();
		int listBottom = cardsBottom;
		int width = contentRight - contentLeft;
		Theme.inset(graphics, contentLeft, listTop, width, listBottom - listTop);

		int contentHeight = rows.size() * MONEY_ROW_HEIGHT;
		double maxScroll = Math.max(0, contentHeight - (listBottom - listTop - 4));
		moneyScroll = Mth.clamp(moneyScroll, 0, maxScroll);

		graphics.enableScissor(contentLeft + 1, listTop + 1, contentRight - 1, listBottom - 1);
		int y = listTop + 2 - (int) moneyScroll;
		boolean shownDivider = false;
		int rank = 1;
		for (Guide.Entry entry : rows) {
			boolean unlocked = entry.unlocked(profile);
			if (!unlocked && !shownDivider && rank > 1) {
				graphics.fill(contentLeft + 6, y, contentRight - 6, y + 1, Theme.BORDER);
				shownDivider = true;
			}
			if (y + MONEY_ROW_HEIGHT > listTop && y < listBottom) {
				extractMoneyRow(graphics, entry, unlocked, unlocked ? rank : 0, y, mouseX, mouseY, listTop, listBottom);
			}
			if (unlocked) {
				rank++;
			}
			y += MONEY_ROW_HEIGHT;
		}
		graphics.disableScissor();
		extractScrollbar(graphics, listTop, listBottom, contentHeight, maxScroll);
	}

	private void extractMoneyRow(GuiGraphicsExtractor graphics, Guide.Entry entry, boolean unlocked, int rank, int y,
								 int mouseX, int mouseY, int listTop, int listBottom) {
		int x = contentLeft + 6;
		int right = contentRight - 8;
		boolean hovered = mouseX >= contentLeft && mouseX < contentRight && mouseY >= Math.max(y, listTop) && mouseY < Math.min(y + MONEY_ROW_HEIGHT, listBottom);
		if (hovered) {
			graphics.fill(contentLeft + 1, y, contentRight - 1, y + MONEY_ROW_HEIGHT, 0x20FFFFFF);
			graphics.setTooltipForNextFrame(font, entryDetails(entry, unlocked), mouseX, mouseY);
		}

		String prefix = unlocked ? rank + ". " : "";
		int nameX = x;
		if (!prefix.isEmpty()) {
			graphics.text(font, prefix, x, y + 3, rank == 1 ? Theme.COINS : Theme.MUTED, false);
			nameX += font.width("00. ");
		}
		String price = priceSummary(entry);
		int priceWidth = price.isEmpty() ? 0 : font.width(price) + 8;
		drawTrimmed(graphics, Component.literal(entry.name()).withStyle(color(unlocked ? (rank == 1 ? Theme.COINS : Theme.TEXT) : 0xFF888888)),
				nameX, y + 3, right - priceWidth - nameX);
		if (!price.isEmpty()) {
			graphics.text(font, price, right - font.width(price), y + 3, unlocked ? Theme.GOOD : 0xFF557755, false);
		}
		Component second = unlocked
				? Component.literal(entry.info()).withStyle(color(Theme.MUTED))
				: Component.literal("Needs " + String.join(", ", entry.missing(profile))).withStyle(color(0xFFCC4444));
		drawTrimmed(graphics, second, nameX, y + 13, right - nameX);
	}

	/** "Enchanted Sugar Cane 97.5k" for the most valuable product of a method. */
	private String priceSummary(Guide.Entry entry) {
		String bestId = null;
		double bestPrice = 0;
		for (String id : entry.products()) {
			Double price = prices.sellPrice(id);
			if (price != null && price > bestPrice) {
				bestPrice = price;
				bestId = id;
			}
		}
		return bestId == null ? "" : shortName(bestId) + " " + coins(bestPrice);
	}

	private static String shortName(String productId) {
		return Requirement.pretty(productId).replace("Enchanted ", "Ench. ").replace("Nether Stalk", "Nether Wart");
	}

	static String coins(double value) {
		if (value >= 1e6) {
			return String.format(Locale.ROOT, "%.2fm", value / 1e6);
		}
		if (value >= 1e3) {
			return String.format(Locale.ROOT, "%.1fk", value / 1e3);
		}
		return String.format(Locale.ROOT, "%.1f", value);
	}

	// ---------------------------------------------------------------- tooltips

	private List<FormattedCharSequence> entryDetails(Guide.Entry entry, boolean unlocked) {
		List<Component> lines = new ArrayList<>();
		lines.add(Component.literal(entry.name()).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
		for (FormattedCharSequence wrapped : font.split(Component.literal(entry.info()), 220)) {
			lines.add(Component.empty().append(toComponent(wrapped)).withStyle(ChatFormatting.GRAY));
		}
		if (!entry.products().isEmpty()) {
			lines.add(Component.empty());
			lines.add(Component.literal("Bazaar sell prices:").withStyle(ChatFormatting.GOLD));
			for (String id : entry.products()) {
				Double price = prices.sellPrice(id);
				lines.add(Component.literal("  " + Requirement.pretty(id).replace("Nether Stalk", "Nether Wart") + ": ")
						.withStyle(ChatFormatting.GRAY)
						.append(Component.literal(price == null ? "?" : coins(price)).withStyle(ChatFormatting.GREEN)));
			}
		}
		if (!entry.requires().isEmpty()) {
			lines.add(Component.empty());
			lines.add(Component.literal("Suggested requirements:").withStyle(ChatFormatting.GOLD));
			for (Requirement r : entry.requires()) {
				boolean met = profile.meets(r);
				lines.add(Component.literal("  " + (met ? "✔ " : "✖ ") + r.text()).withStyle(met ? ChatFormatting.GREEN : ChatFormatting.RED));
			}
		}
		lines.add(Component.empty());
		lines.add(Component.literal(unlocked ? "You can do this now." : "Not unlocked yet.").withStyle(ChatFormatting.DARK_GRAY));
		return lines.stream().map(Component::getVisualOrderText).toList();
	}

	private static Component toComponent(FormattedCharSequence sequence) {
		StringBuilder text = new StringBuilder();
		sequence.accept((index, style, codePoint) -> {
			text.appendCodePoint(codePoint);
			return true;
		});
		return Component.literal(text.toString());
	}

	private List<FormattedCharSequence> details(Recommender.Option option, Map<String, Double> weights, List<String> similar) {
		boolean isBest = similar != null;
		List<Component> lines = new ArrayList<>();
		lines.add(Component.literal(option.name()).withStyle(color(rarityColor(option.rarity())).withBold(true)));
		lines.add(Component.literal(Requirement.pretty(option.rarity()) + " • " + option.stageName() + " stage").withStyle(ChatFormatting.DARK_GRAY));
		if (option.pieces().size() > 1) {
			lines.add(Component.empty());
			for (GearItem piece : option.pieces()) {
				lines.add(Component.literal("  " + piece.name()).withStyle(ChatFormatting.GRAY));
			}
		}

		lines.add(Component.empty());
		lines.add(Component.literal(option.pieces().size() > 1 ? "Full set stats:" : "Stats:").withStyle(ChatFormatting.GOLD));
		List<Map.Entry<String, Double>> stats = new ArrayList<>(option.stats().entrySet());
		stats.sort((a, b) -> Double.compare(weights.getOrDefault(b.getKey(), 0.0) * Math.abs(b.getValue()),
				weights.getOrDefault(a.getKey(), 0.0) * Math.abs(a.getValue())));
		for (Map.Entry<String, Double> stat : stats.subList(0, Math.min(8, stats.size()))) {
			boolean important = weights.containsKey(stat.getKey());
			lines.add(Component.literal("  +" + Recommender.number(stat.getValue()) + " " + Requirement.pretty(stat.getKey()))
					.withStyle(important ? ChatFormatting.GREEN : ChatFormatting.GRAY));
		}
		if (stats.isEmpty()) {
			lines.add(Component.literal("  Upgrades as you use it (levelable tool)").withStyle(ChatFormatting.GRAY));
		}

		if (!option.requirements().isEmpty()) {
			lines.add(Component.empty());
			lines.add(Component.literal("Requirements:").withStyle(ChatFormatting.GOLD));
			for (Requirement r : option.requirements()) {
				if (!profile.tracks(r)) {
					lines.add(Component.literal("  ? " + r.text() + " (no data yet: counts as not done)").withStyle(ChatFormatting.YELLOW));
				} else if (profile.meets(r)) {
					lines.add(Component.literal("  ✔ " + r.text()).withStyle(ChatFormatting.GREEN));
				} else {
					lines.add(Component.literal("  ✖ " + r.text()).withStyle(ChatFormatting.RED));
				}
			}
		}
		if (isBest && !similar.isEmpty()) {
			lines.add(Component.empty());
			lines.add(Component.literal("Just as good:").withStyle(ChatFormatting.GOLD));
			for (String name : similar.subList(0, Math.min(8, similar.size()))) {
				lines.add(Component.literal("  " + name).withStyle(ChatFormatting.GRAY));
			}
			if (similar.size() > 8) {
				lines.add(Component.literal("  ...and " + (similar.size() - 8) + " more").withStyle(ChatFormatting.DARK_GRAY));
			}
		}
		lines.add(Component.empty());
		lines.add(Component.literal(isBest ? "Best one you meet the requirements for." : "Your next upgrade in this slot.")
				.withStyle(ChatFormatting.DARK_GRAY));
		lines.add(Component.literal("Click to find it on the auction house").withStyle(ChatFormatting.YELLOW));
		lines.add(Component.literal("Ctrl+click to view its recipe").withStyle(ChatFormatting.YELLOW));
		boolean pinned = profile.pins.contains("ITEM:" + Advisor.recipeId(option));
		lines.add(Component.literal(pinned ? "Shift+click to unpin from your HUD" : "Shift+click to pin to your HUD").withStyle(ChatFormatting.AQUA));
		return lines.stream().map(Component::getVisualOrderText).toList();
	}

	// ---------------------------------------------------------------- helpers

	private static Style color(int argb) {
		return Style.EMPTY.withColor(TextColor.fromRgb(argb & 0xFFFFFF));
	}

	private boolean hovered(int mouseX, int mouseY, int x, int y, int width) {
		return mouseX >= x && mouseX < x + width && mouseY >= y - 2 && mouseY < y + font.lineHeight + 1;
	}

	private void drawTrimmed(GuiGraphicsExtractor graphics, Component text, int x, int y, int maxWidth) {
		if (font.width(text) <= maxWidth) {
			graphics.text(font, text, x, y, Theme.TEXT, false);
			return;
		}
		FormattedCharSequence cut = Language.getInstance().getVisualOrder(font.substrByWidth(text, maxWidth - font.width("...")));
		graphics.text(font, cut, x, y, Theme.TEXT, false);
		graphics.text(font, "...", x + font.width(cut), y, Theme.MUTED, false);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (event.button() == 0 && scrollbarClicked(event.x(), event.y())) {
			return true;
		}
		if (event.button() == 0 && selected != null && skillHeaderClicked(event.x(), event.y())) {
			return true;
		}
		if (event.button() == 0) {
			if (event.hasShiftDown() && pinClicked(event.x(), event.y())) {
				return true;
			}
			for (RecipeTarget t : recipeTargets) {
				if (event.x() >= t.x0() && event.x() < t.x1() && event.y() >= t.y0() && event.y() < t.y1()) {
					if (event.hasControlDown()) {
						openRecipe(t.id());
					} else {
						openMarket(t.id());
					}
					return true;
				}
			}
		}
		if (selected == null && !extraPage.equals("MONEY") && !extraPage.equals("SOULS") && infoClicked(event.x(), event.y(), event.button())) {
			return true;
		}
		if (event.button() != 0) {
			return false;
		}
		if (selected == null && extraPage.equals("SOULS") && soulsClicked(event.x(), event.y())) {
			return true;
		}
		int tab = tabAt(event.x(), event.y());
		if (tab < 0) {
			return false;
		}
		selected = tab < MONEY_TAB ? SkillCategory.values()[tab] : null;
		if (selected == null) {
			extraPage = EXTRA_PAGES.get(tab - MONEY_TAB);
		}
		profile.lastCategory = selected == null ? extraPage : selected.name();
		profile.save();
		searchBox.setValue("");
		moneyScroll = 0;
		return true;
	}

	/** Opens the item in the bazaar if it's sold there, otherwise searches the auction house for it. */
	private void openMarket(String id) {
		if (minecraft.player == null) {
			return;
		}
		String name = ProgressionClient.get().database().name(id).replaceAll("§.", "");
		minecraft.gui.setScreen(null);
		boolean bazaar = ProgressionClient.get().prices().buyPrice(id) != null;
		minecraft.player.connection.sendCommand((bazaar ? "bz " : "ahs ") + name);
	}

	/** Hypixel's /viewrecipe opens SkyBlock's own recipe menu for the item. */
	private void openRecipe(String id) {
		if (minecraft.player == null) {
			return;
		}
		minecraft.gui.setScreen(null);
		minecraft.player.connection.sendCommand("viewrecipe " + id);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		// Anywhere on the page, not just over the list. Some clients send sideways or tiny scroll amounts.
		double amount = scrollY != 0 ? scrollY : scrollX;
		if (selected == null && amount != 0 && mouseX >= left && mouseX < left + panelWidth && mouseY >= top && mouseY < top + panelHeight
				&& tabAt(mouseX, mouseY) < 0) {
			double step = extraPage.equals("SOULS") ? SOUL_ROW_HEIGHT * 3 : MONEY_ROW_HEIGHT;
			moneyScroll = Math.max(0, moneyScroll - Math.signum(amount) * Math.max(1, Math.abs(amount)) * step);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	// ---------------------------------------------------------------- fairy souls page

	/** Unfound souls first; closest first when you're on that island. */
	private List<FairySouls.Soul> soulRows() {
		List<FairySouls.Soul> souls = new ArrayList<>(ProgressionClient.get().fairySouls().islands().getOrDefault(soulIsland, List.of()));
		LocalPlayer player = minecraft.player;
		boolean here = player != null && soulIsland.equals(FairySouls.currentIsland(minecraft));
		souls.sort((a, b) -> {
			boolean fa = profile.foundSouls.contains(a.key());
			boolean fb = profile.foundSouls.contains(b.key());
			if (fa != fb) {
				return fa ? 1 : -1;
			}
			if (here) {
				return Double.compare(a.distanceTo(player.getX(), player.getY(), player.getZ()),
						b.distanceTo(player.getX(), player.getY(), player.getZ()));
			}
			return Integer.compare(a.index(), b.index());
		});
		return souls;
	}

	private List<String> islandOrder() {
		return new ArrayList<>(ProgressionClient.get().fairySouls().islands().keySet());
	}

	private void extractSouls(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		FairySouls fairySouls = ProgressionClient.get().fairySouls();
		int headerY = top + Theme.HEADER_HEIGHT + 9;
		graphics.text(font, "Fairy Souls", contentLeft, headerY, 0xFFFF55FF, true);
		if (!fairySouls.ready()) {
			graphics.text(font, fairySouls.status(), contentLeft, cardsTop + 4, Theme.MUTED, false);
			return;
		}
		String totals = "  " + profile.foundSouls.size() + "/" + fairySouls.total() + " found";
		graphics.text(font, totals, contentLeft + font.width("Fairy Souls"), headerY, Theme.MUTED, true);

		// Island switcher: "<  Hub 12/80  >"
		List<FairySouls.Soul> all = fairySouls.islands().getOrDefault(soulIsland, List.of());
		long found = all.stream().filter(s -> profile.foundSouls.contains(s.key())).count();
		String here = FairySouls.currentIsland(minecraft);
		String islandText = FairySouls.displayName(soulIsland) + " " + found + "/" + all.size()
				+ (soulIsland.equals(here) ? " (you're here)" : "");
		int arrowWidth = font.width("<") + 8;
		int islandRight = contentRight;
		int islandLeft = islandRight - arrowWidth * 2 - font.width(islandText) - 8;
		switcherLeft = islandLeft;
		switcherRight = islandRight;
		switcherArrow = arrowWidth;
		boolean overLeft = mouseY >= headerY - 3 && mouseY < headerY + 11 && mouseX >= islandLeft && mouseX < islandLeft + arrowWidth;
		boolean overRight = mouseY >= headerY - 3 && mouseY < headerY + 11 && mouseX >= islandRight - arrowWidth && mouseX < islandRight;
		graphics.text(font, "<", islandLeft + 4, headerY, overLeft ? Theme.HIGHLIGHT : Theme.TEXT, false);
		graphics.text(font, islandText, islandLeft + arrowWidth + 4, headerY, soulIsland.equals(here) ? Theme.GOOD : Theme.ACCENT, false);
		graphics.text(font, ">", islandRight - arrowWidth + 4, headerY, overRight ? Theme.HIGHLIGHT : Theme.TEXT, false);

		int listTop = moneyListTop();
		int listBottom = cardsBottom;
		Theme.inset(graphics, contentLeft, listTop, contentRight - contentLeft, listBottom - listTop);
		List<FairySouls.Soul> rows = soulRows();
		int contentHeight = rows.size() * SOUL_ROW_HEIGHT + 14;
		double maxScroll = Math.max(0, contentHeight - (listBottom - listTop - 4));
		moneyScroll = Mth.clamp(moneyScroll, 0, maxScroll);

		LocalPlayer player = minecraft.player;
		boolean onIsland = player != null && soulIsland.equals(here);
		graphics.enableScissor(contentLeft + 1, listTop + 1, contentRight - 1, listBottom - 1);
		int y = listTop + 3 - (int) moneyScroll;
		graphics.text(font, onIsland ? "Closest first. Click one to mark it found." : "Click a soul to mark it found.",
				contentLeft + 6, y, 0xFF777777, false);
		String toggle = outlineToggleText();
		boolean overToggle = mouseX >= contentRight - 8 - font.width(toggle) && mouseX < contentRight && mouseY >= y - 2 && mouseY < y + 10;
		graphics.text(font, toggle, contentRight - 8 - font.width(toggle), y,
				profile.soulOutlines ? (overToggle ? 0xFFFFAAFF : 0xFFFF55FF) : (overToggle ? Theme.TEXT : 0xFF777777), false);
		if (overToggle) {
			graphics.setTooltipForNextFrame(font, List.of(
					Component.literal("Pink boxes, beams and labels on the souls").getVisualOrderText(),
					Component.literal("you haven't found, visible through walls").getVisualOrderText(),
					Component.literal("on your current island. Click to toggle.").getVisualOrderText()), mouseX, mouseY);
		}
		y += 14;
		for (FairySouls.Soul soul : rows) {
			if (y + SOUL_ROW_HEIGHT > listTop && y < listBottom) {
				boolean isFound = profile.foundSouls.contains(soul.key());
				boolean hovered = mouseX >= contentLeft && mouseX < contentRight && mouseY >= Math.max(y - 2, listTop)
						&& mouseY < Math.min(y + SOUL_ROW_HEIGHT - 2, listBottom);
				if (hovered) {
					graphics.fill(contentLeft + 1, y - 2, contentRight - 1, y + SOUL_ROW_HEIGHT - 2, 0x20FFFFFF);
				}
				int x = contentLeft + 6;
				graphics.text(font, "#" + soul.index(), x, y, isFound ? 0xFF555555 : Theme.MUTED, false);
				String coords = soul.x() + ", " + soul.y() + ", " + soul.z();
				graphics.text(font, coords, x + font.width("#00 "), y, isFound ? 0xFF666666 : Theme.TEXT, false);

				String right;
				int rightColor;
				if (isFound) {
					right = "✔ Found";
					rightColor = Theme.GOOD;
				} else if (onIsland) {
					right = direction(soul, player);
					rightColor = Theme.COINS;
				} else {
					right = hovered ? "Mark found" : "";
					rightColor = Theme.MUTED;
				}
				graphics.text(font, right, contentRight - 8 - font.width(right), y, rightColor, false);
			}
			y += SOUL_ROW_HEIGHT;
		}
		graphics.disableScissor();
		extractScrollbar(graphics, listTop, listBottom, contentHeight, maxScroll);
	}

	/** "34m ↗ +12y": distance, which way to turn, and how far up or down. */
	private static String direction(FairySouls.Soul soul, LocalPlayer player) {
		double dx = soul.x() + 0.5 - player.getX();
		double dz = soul.z() + 0.5 - player.getZ();
		double dy = soul.y() - player.getY();
		double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
		double relative = Mth.wrapDegrees(targetYaw - player.getYRot());
		String arrow = ARROWS[Math.floorMod((int) Math.round(relative / 45.0), 8)];
		String height = Math.abs(dy) < 3 ? "" : String.format(Locale.ROOT, " %+dy", (int) Math.round(dy));
		return Math.round(soul.distanceTo(player.getX(), player.getY(), player.getZ())) + "m " + arrow + height;
	}

	private String outlineToggleText() {
		return "Outlines: " + (profile.soulOutlines ? "ON" : "OFF");
	}

	private boolean soulsClicked(double mouseX, double mouseY) {
		FairySouls fairySouls = ProgressionClient.get().fairySouls();
		if (!fairySouls.ready()) {
			return false;
		}
		int headerY = top + Theme.HEADER_HEIGHT + 9;
		boolean onLeft = mouseX >= switcherLeft && mouseX < switcherLeft + switcherArrow;
		boolean onRight = mouseX >= switcherRight - switcherArrow && mouseX < switcherRight;
		if (mouseY >= headerY - 3 && mouseY < headerY + 11 && (onLeft || onRight)) {
			List<String> islands = islandOrder();
			int index = Math.max(0, islands.indexOf(soulIsland));
			int step = onLeft ? -1 : 1;
			soulIsland = islands.get(Math.floorMod(index + step, islands.size()));
			moneyScroll = 0;
			return true;
		}
		int listTop = moneyListTop();
		if (mouseX < contentLeft || mouseX >= contentRight || mouseY < listTop || mouseY >= cardsBottom) {
			return false;
		}
		double hintY = listTop + 3 - moneyScroll;
		if (mouseY >= hintY - 2 && mouseY < hintY + 10 && mouseX >= contentRight - 8 - font.width(outlineToggleText())) {
			profile.soulOutlines = !profile.soulOutlines;
			profile.save();
			return true;
		}
		int row = (int) Math.floor((mouseY - (listTop + 3 + 14 - moneyScroll) + 2) / SOUL_ROW_HEIGHT);
		List<FairySouls.Soul> rows = soulRows();
		if (row < 0 || row >= rows.size()) {
			return false;
		}
		String key = rows.get(row).key();
		if (!profile.foundSouls.remove(key)) {
			profile.foundSouls.add(key);
		}
		ProgressionClient.get().profileChanged();
		return true;
	}

	static int rarityColor(String rarity) {
		return switch (rarity) {
			case "UNCOMMON" -> 0xFF55FF55;
			case "RARE" -> 0xFF5555FF;
			case "EPIC" -> 0xFFAA00AA;
			case "LEGENDARY" -> 0xFFFFAA00;
			case "MYTHIC" -> 0xFFFF55FF;
			case "DIVINE" -> 0xFF55FFFF;
			case "SPECIAL", "VERY_SPECIAL" -> 0xFFFF5555;
			default -> 0xFFFFFFFF;
		};
	}
}
