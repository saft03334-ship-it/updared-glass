package com.example.glassbot;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import com.example.glassbot.BotConfig.Spot;

public class GlassBotClient implements ClientModInitializer {
	public static final String MOD_ID = "glassbot";
	private static KeyMapping toggleKey;

	@Override
	public void onInitializeClient() {
		BotConfig.load();

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		toggleKey = KeyBindingHelper.registerKeyBinding(
				new KeyMapping("key.glassbot.toggle", InputConstants.Type.KEYSYM, InputConstants.KEY_J, category));

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (toggleKey.consumeClick()) {
				if (Bot.running) {
					Bot.stop("Stopped");
				} else {
					String err = Bot.start();
					if (err != null) Bot.say(err);
				}
			}
			Bot.tick(mc);
		});

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			LiteralArgumentBuilder<FabricClientCommandSource> root = ClientCommandManager.literal("glassbot");

			LiteralArgumentBuilder<FabricClientCommandSource> set = ClientCommandManager.literal("set");
			for (Spot s : Spot.values()) {
				set.then(ClientCommandManager.literal(s.id).executes(ctx -> {
					setSpot(ctx.getSource(), s);
					return 1;
				}));
			}
			root.then(set);

			root.then(ClientCommandManager.literal("start").executes(ctx -> {
				String err = Bot.start();
				if (err != null) ctx.getSource().sendFeedback(Component.literal("[GlassBot] " + err));
				return 1;
			}));
			root.then(ClientCommandManager.literal("stop").executes(ctx -> {
				if (Bot.running) Bot.stop("Stopped");
				return 1;
			}));
			root.then(ClientCommandManager.literal("status").executes(ctx -> {
				for (Spot s : Spot.values()) {
					BlockPos p = BotConfig.get(s);
					ctx.getSource().sendFeedback(Component.literal(s.label + ": " + (p == null ? "not set" : p.getX() + " " + p.getY() + " " + p.getZ())));
				}
				ctx.getSource().sendFeedback(Component.literal("Queue radius: " + BotConfig.queueRadius() + ", bookshelf radius: " + BotConfig.bookRadius()));
				return 1;
			}));
			root.then(ClientCommandManager.literal("radius")
					.then(ClientCommandManager.literal("queue").then(ClientCommandManager.argument("blocks", IntegerArgumentType.integer(2, 40)).executes(ctx -> {
						BotConfig.queueRadius(IntegerArgumentType.getInteger(ctx, "blocks"));
						ctx.getSource().sendFeedback(Component.literal("Queue radius set."));
						return 1;
					})))
					.then(ClientCommandManager.literal("books").then(ClientCommandManager.argument("blocks", IntegerArgumentType.integer(2, 20)).executes(ctx -> {
						BotConfig.bookRadius(IntegerArgumentType.getInteger(ctx, "blocks"));
						ctx.getSource().sendFeedback(Component.literal("Bookshelf radius set."));
						return 1;
					}))));
			dispatcher.register(root);
		});
	}

	/** Uses the block you are looking at, or the block you are standing on if you aren't looking at one. */
	private static void setSpot(FabricClientCommandSource src, Spot s) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		BlockPos p;
		if (mc.hitResult instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK) p = b.getBlockPos();
		else p = mc.player.blockPosition();
		BotConfig.set(s, p);
		src.sendFeedback(Component.literal("[GlassBot] " + s.label + " set to " + p.getX() + " " + p.getY() + " " + p.getZ()));
	}
}
