package com.example.glassbot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.example.glassbot.BotConfig.Spot;

/** The decision-maker: picks the next villager, plans trips, and sorts villagers into the two drop-off spots. */
final class Bot {
	enum Kind { NONE, GLASS, PROGRESS, EXHAUSTED, NO_TRADES, NEED_BOOKS, NEED_EMERALDS, NEED_DUMP }

	/** Written by Tasks.Session when it finishes. */
	static Kind pending = Kind.NONE;
	static int needBooks;
	static boolean emeraldsEmpty;

	static boolean running;
	private static final Deque<Tasks.Task> queue = new ArrayDeque<>();
	private static Tasks.Task current;
	private static Villager cur;
	private static final Set<UUID> done = new HashSet<>();
	private static final Map<UUID, Integer> failCount = new HashMap<>();
	private static int tick, fails, idle, glassCount, otherCount, bookTrips, emTrips, dumpTrips;

	private Bot() {}

	// ------------------------------------------------------------------ control

	static String start() {
		if (BotConfig.get(Spot.QUEUE) == null) return "Set the villager queue first: look at it and run /glassbot set queue";
		if (BotConfig.get(Spot.GLASS) == null) return "Set the glass villager spot first: /glassbot set glass";
		if (BotConfig.get(Spot.NONGLASS) == null) return "Set the non-glass villager spot first: /glassbot set nonglass";
		queue.clear();
		current = null;
		cur = null;
		done.clear();
		failCount.clear();
		pending = Kind.NONE;
		needBooks = 0;
		emeraldsEmpty = false;
		tick = fails = idle = glassCount = otherCount = bookTrips = emTrips = dumpTrips = 0;
		running = true;
		say("Started. Press the toggle key or run /glassbot stop to stop.");
		return null;
	}

	static void stop(String why) {
		boolean was = running;
		running = false;
		queue.clear();
		current = null;
		Minecraft mc = Minecraft.getInstance();
		Mover.releaseAll(mc);
		if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
		if (was) say(why + " (" + glassCount + " glass, " + otherCount + " other)");
	}

	static void say(String s) {
		LocalPlayer p = Minecraft.getInstance().player;
		if (p != null) p.displayClientMessage(Component.literal("[GlassBot] " + s), false);
	}

	/** Block cells of minecarts that carry villagers (the pathfinder steers around them). */
	static Set<Long> cartCells(ClientLevel lv, LocalPlayer p) {
		Set<Long> out = new HashSet<>();
		for (Villager v : lv.getEntitiesOfClass(Villager.class, p.getBoundingBox().inflate(48))) {
			Entity c = v.getVehicle();
			if (c != null) out.add(c.blockPosition().asLong());
		}
		return out;
	}

	// ------------------------------------------------------------------ tick

	static void tick(Minecraft mc) {
		if (!running) return;
		LocalPlayer p = mc.player;
		ClientLevel lv = mc.level;
		if (p == null || lv == null || !p.isAlive()) {
			stop("Stopped");
			return;
		}
		boolean inContainer = p.containerMenu != p.inventoryMenu;
		if (mc.screen != null && !inContainer) { // some other menu (chat, pause, inventory) is open: hold still
			Mover.releaseAll(mc);
			return;
		}
		tick++;
		if (idle > 0) {
			idle--;
			return;
		}

		if (current == null) {
			current = queue.poll();
			if (current == null) {
				plan(mc, p, lv);
				if (!running) return;
				current = queue.poll();
				if (current == null) return;
			}
		}

		Tasks.Status s = current.tick(mc, p, lv);
		if (s == Tasks.Status.DONE) {
			current = null;
		} else if (s == Tasks.Status.FAILED) {
			onFail(mc, p);
		}
		if (running && tick % 80 == 0 && current != null) {
			p.displayClientMessage(Component.literal("GlassBot: " + current.name() + " | " + glassCount + " glass, " + otherCount + " other"), true);
		}
	}

	private static void onFail(Minecraft mc, LocalPlayer p) {
		String what = current == null ? "task" : current.name();
		Mover.releaseAll(mc);
		if (p.containerMenu != p.inventoryMenu) p.closeContainer();
		queue.clear();
		current = null;
		pending = Kind.NONE;
		fails++;
		say(what + " failed (" + fails + "/6) - retrying.");
		if (cur != null) {
			int f = failCount.merge(cur.getUUID(), 1, Integer::sum);
			if (f >= 3) {
				say("Giving up on this villager for now and skipping it.");
				done.add(cur.getUUID());
				cur = null;
			}
		}
		if (fails >= 6) stop("Stopped after repeated failures");
		idle = 40;
	}

	// ------------------------------------------------------------------ planning

	private static void trip(Spot spot, Tasks.Task... after) {
		BlockPos pos = BotConfig.get(spot);
		queue.add(new Tasks.Walk(Goals.nearBlock(pos), Vec3.atCenterOf(pos)));
		queue.add(new Tasks.UseBlock(pos));
		for (Tasks.Task t : after) queue.add(t);
	}

	private static void emeraldTrip() {
		trip(Spot.EMERALD, new Tasks.TakeBlocks(), new Tasks.Wait(12), new Tasks.Craft());
	}

	private static boolean booksTrip(LocalPlayer p, ClientLevel lv, int need) {
		BlockPos c = BotConfig.get(Spot.BOOKS);
		int r = BotConfig.bookRadius();
		List<BlockPos> shelves = new ArrayList<>();
		for (int x = -r; x <= r; x++)
			for (int y = -r; y <= r; y++)
				for (int z = -r; z <= r; z++) {
					BlockPos b = c.offset(x, y, z);
					if (lv.getBlockState(b).is(Blocks.BOOKSHELF)) shelves.add(b);
				}
		if (shelves.isEmpty()) return false;

		int want = Math.min(Math.max(1, (need + 2) / 3), 8); // each shelf drops 3 books
		BlockPos from = p.blockPosition();
		for (int i = 0; i < want && !shelves.isEmpty(); i++) {
			BlockPos ref = from;
			BlockPos nearest = shelves.get(0);
			for (BlockPos s : shelves) if (s.distSqr(ref) < nearest.distSqr(ref)) nearest = s;
			shelves.remove(nearest);
			from = nearest;
			queue.add(new Tasks.Walk(Goals.nearBlock(nearest), Vec3.atCenterOf(nearest)));
			queue.add(new Tasks.Mine(nearest));
		}
		queue.add(new Tasks.Pickup(Vec3.atCenterOf(c)));
		return true;
	}

	private static void queuePush(Villager v, Spot spot, boolean glass) {
		BlockPos dest = BotConfig.get(spot);
		queue.add(new Tasks.Push(v, dest));
		queue.add(new Tasks.Run(() -> {
			done.add(v.getUUID());
			if (glass) glassCount++;
			else otherCount++;
			cur = null;
			fails = 0;
			say((glass ? "Glass villager" : "Non-glass villager") + " moved. Total: " + glassCount + " glass, " + otherCount + " other.");
		}));
	}

	private static Villager pickVillager(LocalPlayer p, ClientLevel lv) {
		BlockPos q = BotConfig.get(Spot.QUEUE);
		BlockPos g = BotConfig.get(Spot.GLASS);
		BlockPos n = BotConfig.get(Spot.NONGLASS);
		double r = BotConfig.queueRadius();
		AABB box = new AABB(q.getX() + 0.5 - r, q.getY() - 4, q.getZ() + 0.5 - r, q.getX() + 0.5 + r, q.getY() + 6, q.getZ() + 0.5 + r);
		Villager best = null;
		for (Villager v : lv.getEntitiesOfClass(Villager.class, box)) {
			Entity cart = v.getVehicle();
			if (cart == null || cart.getType() != EntityType.MINECART || v.isBaby() || done.contains(v.getUUID())) continue;
			if (near(v, g, 3.0) || near(v, n, 3.0)) continue; // already sitting in a drop-off spot
			if (best == null || v.distanceToSqr(p) < best.distanceToSqr(p)) best = v;
		}
		return best;
	}

	private static boolean near(Entity e, BlockPos p, double r) {
		return Math.hypot(e.getX() - (p.getX() + 0.5), e.getZ() - (p.getZ() + 0.5)) <= r;
	}

	private static void plan(Minecraft mc, LocalPlayer p, ClientLevel lv) {
		Kind k = pending;
		pending = Kind.NONE;

		// 1) inventory full of junk -> dump
		BlockPos dump = BotConfig.get(Spot.DUMP);
		if (k == Kind.NEED_DUMP || (dump != null && Inv.freeSlots(p) <= 6 && Inv.junkStacks(p) > 0)) {
			if (dump == null) {
				stop("Inventory is full and no dump container is set (/glassbot set dump)");
				return;
			}
			if (++dumpTrips > 6) {
				stop("Dumping is not freeing space - is the dump container full?");
				return;
			}
			trip(Spot.DUMP, new Tasks.Dump());
			return;
		}
		dumpTrips = 0;

		// 2) which villager are we working on?
		if (cur == null || done.contains(cur.getUUID()) || !cur.isAlive() || cur.getVehicle() == null) {
			cur = pickVillager(p, lv);
			bookTrips = 0;
			emTrips = 0;
			if (cur == null) {
				stop("Finished - no more unprocessed villagers in the queue area");
				return;
			}
			k = Kind.NONE;
		}

		// 3) act on what the last trading session found
		switch (k) {
			case GLASS -> {
				queuePush(cur, Spot.GLASS, true);
				return;
			}
			case NO_TRADES, EXHAUSTED -> {
				queuePush(cur, Spot.NONGLASS, false);
				return;
			}
			case NEED_BOOKS -> {
				int need = needBooks - Inv.count(p, Items.BOOK);
				if (need > 0) {
					if (BotConfig.get(Spot.BOOKS) == null) {
						stop("This villager needs books but no bookshelf area is set (/glassbot set books)");
						return;
					}
					if (bookTrips++ < 4 && booksTrip(p, lv, need)) return;
					stop("This villager needs books but there are no bookshelves left in the bookshelf area");
					return;
				}
				// already holding enough books: just carry on trading
			}
			case NEED_EMERALDS -> {
				if (BotConfig.get(Spot.EMERALD) != null && !emeraldsEmpty && emTrips++ < 3) {
					emeraldTrip();
					return;
				}
				stop("Out of emeralds (nothing left to craft from the emerald container)");
				return;
			}
			default -> { }
		}

		// 4) keep a stock of emeralds
		if (Inv.count(p, Items.EMERALD) < 64) {
			if (Inv.count(p, Items.EMERALD_BLOCK) > 0) {
				queue.add(new Tasks.Craft());
			} else if (BotConfig.get(Spot.EMERALD) != null && !emeraldsEmpty) {
				emeraldTrip();
				return;
			}
		}

		// 5) trade with the villager (after a pause if it needs time to level up)
		if (k == Kind.PROGRESS) queue.add(new Tasks.Wait(100));
		queue.add(new Tasks.Walk(Goals.nearEntity(cur), cur.position()));
		queue.add(new Tasks.Session(cur));
	}
}
