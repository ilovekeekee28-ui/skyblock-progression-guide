package dev.sw1ftz.progression;

import dev.sw1ftz.progression.gui.MoveHudScreen;
import dev.sw1ftz.progression.gui.PinHud;
import dev.sw1ftz.progression.gui.ProgressionScreen;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

public class ProgressionClient implements ClientModInitializer {
	public static final String MOD_ID = "skyblockprogression";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	/** The API is a little behind the game, so re-sync this long after a level-up message. */
	private static final long SYNC_DELAY_AFTER_LEVEL_UP_MS = 90_000;

	private static ProgressionClient instance;

	private PlayerProfile profile;
	private ItemDatabase database;
	private Guide guide;
	private final BazaarPrices prices = new BazaarPrices();
	private final FairySouls fairySouls = new FairySouls();
	private final RepoData repo = new RepoData();
	private final HypixelApi api = new HypixelApi();
	private final MenuReader menus = new MenuReader();
	private final MinionTracker minions = new MinionTracker();
	private final AuctionPrices auctions = new AuctionPrices();
	private KeyMapping openKey;
	private KeyMapping sortKey;
	private final InventorySorter sorter = new InventorySorter();
	private boolean openNextTick;
	private boolean openHudNextTick;
	private int profileVersion;
	private long syncAt;

	public static ProgressionClient get() {
		return instance;
	}

	public PlayerProfile profile() {
		return profile;
	}

	public Guide guide() {
		return guide;
	}

	public FairySouls fairySouls() {
		return fairySouls;
	}

	public BazaarPrices prices() {
		return prices;
	}

	public ItemDatabase database() {
		return database;
	}

	public RepoData repo() {
		return repo;
	}

	public HypixelApi api() {
		return api;
	}

	public MinionTracker minions() {
		return minions;
	}

	public AuctionPrices auctions() {
		return auctions;
	}

	/** Bumped whenever your stats change, so the guide knows to recalculate. */
	public int profileVersion() {
		return profileVersion;
	}

	public void profileChanged() {
		profileVersion++;
		profile.save();
	}

	@Override
	public void onInitializeClient() {
		instance = this;
		profile = PlayerProfile.load();
		database = new ItemDatabase();
		database.load(false);
		guide = Guide.load();
		fairySouls.load();
		repo.load();

		// Hides the reply to the /locraw we send on joining each island.
		ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> overlay || FairySouls.onLocraw(message.getString()));
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (overlay) {
				return;
			}
			String text = message.getString();
			fairySouls.onChat(text, profile);
			// "SKILL LEVEL UP", "SLAYER LEVEL UP", "COLLECTION LEVEL UP", Catacombs/HOTM level ups...
			if (text.contains("LEVEL UP") || text.contains("Trophy Hunter") || text.contains("KUUDRA DOWN")) {
				syncAt = System.currentTimeMillis() + SYNC_DELAY_AFTER_LEVEL_UP_MS;
			}
		});

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.skyblockprogression.open", GLFW.GLFW_KEY_J, category));
		sortKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.skyblockprogression.sort", GLFW.GLFW_KEY_O, category));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openKey.consumeClick()) {
				openNextTick = true;
			}
			// Deferred one tick so a command typed in chat doesn't get its screen closed by the chat closing.
			if (openNextTick && client.gui.screen() == null) {
				openNextTick = false;
				client.gui.setScreen(new ProgressionScreen(null));
			}
			if (openHudNextTick && client.gui.screen() == null) {
				openHudNextTick = false;
				client.gui.setScreen(new MoveHudScreen(null));
			}
			if (syncAt != 0 && System.currentTimeMillis() >= syncAt) {
				syncAt = 0;
				api.sync(profile);
			}
			FairySouls.tickLocation(client);
			sorter.tick(client, sortKey, profile);
			CopperTracker.tick(client, profile);
			menus.tick(client, profile);
			minions.tick(client, profile);
			fairySouls.drawOutlines(client, profile);
		});

		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "pins"), (graphics, deltaTracker) -> {
			// The move screen draws the HUD itself.
			if (!(Minecraft.getInstance().gui.screen() instanceof MoveHudScreen)) {
				PinHud.draw(graphics, false);
			}
		});

		DevShots.register();
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
			dispatcher.register(literal("sbprog").executes(ctx -> {
				openNextTick = true;
				return 1;
			}).then(literal("sync").executes(ctx -> {
				api.sync(profile);
				return 1;
			})).then(literal("autosort").executes(ctx -> {
				profile.autoSort = !profile.autoSort;
				profile.save();
				ctx.getSource().sendFeedback(net.minecraft.network.chat.Component.literal("Auto-sort is now " + (profile.autoSort ? "ON. Press your sort key (O) in your inventory, ender chest or a backpack." : "OFF.")));
				return 1;
			})).then(literal("hud").executes(ctx -> {
				openHudNextTick = true;
				return 1;
			})));
			dispatcher.register(literal("progression").executes(ctx -> {
				openNextTick = true;
				return 1;
			}));
		});
	}
}
