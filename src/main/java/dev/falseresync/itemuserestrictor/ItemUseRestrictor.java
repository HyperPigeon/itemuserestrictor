package dev.falseresync.itemuserestrictor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.serialization.JsonOps;
import net.fabricmc.api.DedicatedServerModInitializer;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.command.argument.ItemStackArgumentType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static net.minecraft.command.argument.BlockPosArgumentType.blockPos;
import static net.minecraft.command.argument.ItemStackArgumentType.itemStack;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class ItemUseRestrictor implements DedicatedServerModInitializer {
	private static boolean dirty = false;
	private static RuleSet ruleSet = RuleSet.EMPTY;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	public static final Logger LOGGER = LoggerFactory.getLogger("ItemUseRestrictor");
	private static final Path RULE_SET_FILE = FabricLoader.getInstance().getGameDir().resolve("item-use-rule-set.json");

	public static void markDirty() {
		dirty = true;
	}

	@Override
	public void onInitializeServer() {
		loadSavedRuleSet();
		updateRuleSetFile();
		registerCommands();
		trackItemUse();
	}

	private void loadSavedRuleSet() {
		if (RULE_SET_FILE.toFile().exists()) {
			try (var ruleSetFileReader = Files.newBufferedReader(RULE_SET_FILE)) {
				ruleSet = RuleSet.CODEC
						.decode(JsonOps.INSTANCE, JsonParser.parseReader(GSON.newJsonReader(ruleSetFileReader)))
						.getOrThrow(false, LOGGER::error)
						.getFirst();
			} catch (IOException e) {
				LOGGER.atError().setCause(e).setMessage("Could not read item-use-rule-set.json").log();
			}
		} else {
			try (var ruleSetFileWriter = Files.newBufferedWriter(RULE_SET_FILE)) {
				GSON.toJson(
						RuleSet.CODEC.encodeStart(JsonOps.INSTANCE, RuleSet.EMPTY).getOrThrow(false, LOGGER::error),
						GSON.newJsonWriter(ruleSetFileWriter));
			} catch (IOException e) {
				LOGGER.atError().setCause(e).setMessage("Could not save item-use-rule-set.json").log();
			}
		}
	}

	private void updateRuleSetFile() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (!dirty) return;

			try (var ruleSetFileWriter = Files.newBufferedWriter(RULE_SET_FILE)) {
				GSON.toJson(
						RuleSet.CODEC.encodeStart(JsonOps.INSTANCE, ruleSet).getOrThrow(false, LOGGER::error),
						GSON.newJsonWriter(ruleSetFileWriter));
			} catch (IOException e) {
				LOGGER.atError().setCause(e).setMessage("Could not save item-use-rule-set.json").log();
			} finally {
				dirty = false;
			}
		});
	}

	private void trackItemUse() {
		UseItemCallback.EVENT.register(this::onItemUse);
		UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> onItemUse(player, world, hand).getResult());
		UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> onItemUse(player, world, hand).getResult());
	}

	private TypedActionResult<ItemStack> onItemUse(PlayerEntity player, World world, Hand hand) {
		var stack = player.getStackInHand(hand);
		if (world.isClient() || player.isSpectator()) {
			return TypedActionResult.pass(stack);
		}

		if (ruleSet.isAllowed(stack.getItem(), player.getBlockPos())) {
			return TypedActionResult.pass(stack);
		} else {
			return TypedActionResult.fail(stack);
		}
	}

	private void registerCommands() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			var root = literal("item-use")
					.requires(source -> source.hasPermissionLevel(4))
					.build();

			var restrict = literal("restrict").build();
			var itemArg = argument("item", itemStack(registryAccess)).build();
			var posAArg = argument("posA", blockPos()).build();
			var posBArg = argument("posB", blockPos()).executes(this::restrict).build();

			var unrestrict = literal("unrestrict").build();
			var ruleArg = argument("rule", word()).executes(this::unrestrict).build();

			var list = literal("list").executes(this::list).build();

			dispatcher.getRoot().addChild(root);

			root.addChild(restrict);
			restrict.addChild(itemArg);
			itemArg.addChild(posAArg);
			posAArg.addChild(posBArg);

			root.addChild(unrestrict);
			unrestrict.addChild(ruleArg);

			root.addChild(list);
		});
	}

	private int restrict(CommandContext<ServerCommandSource> context) {
		var posA = BlockPosArgumentType.getBlockPos(context, "posA");
		var posB = BlockPosArgumentType.getBlockPos(context, "posB");
		var item = ItemStackArgumentType.getItemStackArgument(context, "item").getItem();
		var id = ruleSet.add(posA, posB, item);
		context.getSource().sendMessage(Text.literal("Created a new restriction rule with id %s".formatted(id)));
		context.getSource().sendMessage(Text.literal("Now restricting %s to an area between %s and %s".formatted(item, posA, posB)));
		return 0;
	}

	private int unrestrict(CommandContext<ServerCommandSource> context) {
		var id = StringArgumentType.getString(context, "rule");
		ruleSet.remove(id);
		context.getSource().sendMessage(Text.literal("Created a new restriction rule with id %s".formatted(id)));
		return 0;
	}

	private int list(CommandContext<ServerCommandSource> context) {
		context.getSource().sendMessage(Text.literal("When a rule is present, a player can only use an item within the ruled zone"));
		context.getSource().sendMessage(Text.literal("When multiple rules for the same item are present, a player can use the item within any of the zones"));
		if (ruleSet.rules().isEmpty()) {
			context.getSource().sendMessage(Text.literal("No rules set yet!").formatted(Formatting.AQUA));
		}
		for (var rule : ruleSet.rules().entrySet()) {
			context.getSource().sendMessage(Text.literal("- Rule %s for item %s restricts to %s".formatted(rule.getKey(), rule.getValue().item(), rule.getValue().box())));
		}
		return 0;
	}
}