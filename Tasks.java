package com.example.glassbot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Small steps the bot strings together. Each tick() returns RUNNING until it is DONE or FAILED. */
final class Tasks {
	enum Status { RUNNING, DONE, FAILED }

	static final Random R = new Random();

	private Tasks() {}

	abstract static class Task {
		abstract Status tick(Minecraft mc, LocalPlayer p, ClientLevel lv);

		String name() {
			return getClass().getSimpleName();
		}
	}

	private static boolean containerOpen(LocalPlayer p) {
		return p.containerMenu != p.inventoryMenu;
	}

	// ------------------------------------------------------------------ basics

	static final class Run extends Task {
		private final Runnable r;

		Run(Runnable r) {
			this.r = r;
		}

		@Override
		Status tick(Minecraft mc, LocalPlayer p, ClientLevel lv) {
			r.run();
			return Status.DONE;
		}
	}

	static final class Wait extends Task {
		private int left;

		Wait(int base) {
			left = base + R.nextInt(Math.max(1, base / 4 + 1));
		}

		@Override
		Status tick(Minecraft mc, LocalPlayer p, ClientLevel lv) {
			Mover.releaseAll(mc);
			return --left <= 0 ? Status.DONE : Status.RUNNING;
		}
	}

	static final class Walk extends Task {
		private final Predicate<BlockPos> goal;
		private final Vec3 hint;
		private final Mover mover = new Mover();
		private boolean planned;
		private int replans;
		private int ticks;

		Walk(Predicate<BlockPos> goal, Vec3 hint) {
			this.goal = goal;
			this.hint = hint;
		}

		@Override
		Status tick(Minecraft mc, LocalPlayer p, ClientLevel lv) {
			if (++ticks > 1500) {
				Mover.releaseAll(mc);
				return Status.FAILED;
			}
			if (!planned) {
				BlockPos start = p.blockPosition();
				if (goal.test(start)) {
					Mover.releaseAll(mc);
					return Status.DONE;
				}
				List<BlockPos> path = Pathfinder.find(lv, start, goal, hint, Bot.cartCells(lv, p), 30000);
				if (path == null) {
					Mover.releaseAll(mc);
					return Status.FAILED;
				}
				mover.setPath(path);
				planned = true;
			}
			switch (mover.tick(mc, p)) {
				case DONE -> {
					if (goal.test(p.blockPosition())) return Status.DONE;
					planned = false;
					if (++replans > 5) return Status.FAILED;
				}
				case STUCK -> {
					planned = false;
					if (++replans > 5) return Status.FAILED;
				}
				default -> { }
			}
			return Status.RUNNING;
		}
	}

	// ------------------------------------------------------------------ interacting

	/** Looks at a block and right-clicks it until a container opens. */
	static final class UseBlock extends Task {
		private final BlockPos pos;
		private final Vec3 point;
		private int ticks, phase, clickTick, aimed, aimNeeded = 1 + R.nextInt(3), misses, attempts;

		UseBlock(BlockPos pos) {
			this.pos = pos;
			this.point = Vec3.atCenterOf(pos).add((R.nextDouble() - 0.5) * 0.4, (R.nextDouble() - 0.5) * 0.4, (R.nextDouble() - 0.5) * 0.4);
		}

		@Override
		Status tick(Minecraft mc, LocalPlayer p, ClientLevel lv) {
			if (containerOpen(p) && mc.screen != null) {
				mc.options.keyUse.setDown(false);
				return Status.DONE;
			}
			if (++ticks > 220) {
				Mover.releaseAll(mc);
				return Status.FAILED;
			}
			if (phase == 1) {
				mc.options.keyUse.setDown(false);
				if (ticks - clickTick > 30) {
					phase = 0;
					if (++attempts >= 3) return Status.FAILED;
				}
				return Status.RUNNING;
			}
			if (!Look.aimAt(p, point)) {
				aimed = 0;
				return Status.RUNNING;
			}
			if (++aimed < aimNeeded) return Status.RUNNING;
			if (mc.hitResult instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK && b.getBlockPos().equals(pos)) {
				mc.options.keyUse.setDown(true);
				phase = 1;
				clickTick = ticks;
			} else if (++misses > 40) {
				return Status.FAILED;
			}
			return Status.RUNNING;
		}
	}

	/** Looks at an entity and right-clicks it until a trading screen opens. */
	static final class UseEntity extends Task {
		private final Entity target;
		private int ticks, phase, clickTick, aimed, aimNeeded = 1 + R.nextInt(3), misses, attempts;
		boolean clicked;
		private final double jx = (R.nextDouble() - 0.5) * 0.2, jy = (R.nextDouble() - 0.5) * 0.2, jz = (R.nextDouble() - 0.5) * 0.2;

		UseEntity(Entity target) {
			this.target = target;
		}

		@Override
		Status tick(Minecraft mc, LocalPlayer p, ClientLevel lv) {
			if (p.containerMenu instanceof MerchantMenu) {
				mc.options.keyUse.setDown(false);
				return Status.DONE;
			}
			if (++ticks > 260 || !target.isAlive()) {
				Mover.releaseAll(mc);
				return Status.FAILED;
			}
			if (phase == 1) {
				mc.options.keyUse.setDown(false);
				if (ticks - clickTick > 30) {
					phase = 0;
					if (++attempts >= 3) return Status.FAILED;
				}
				return Status.RUNNING;
			}
			Vec3 pt = target.getEyePosition().add(jx, -0.15 + jy, jz);
			if (!Look.aimAt(p, pt)) {
				aimed = 0;
				return Status.RUNNING;
			}
			if (++aimed < aimNeeded) return Status.RUNNING;
			if (mc.hitResult instanceof EntityHitResult e && e.getEntity() == target) {
				mc.options.keyUse.setDown(true);
				clicked = true;
				phase = 1;
				clickTick = ticks;
			} else if (++misses > 40) {
				return Status.FAILED;
			}
			return Status.RUNNING;
		}
	}

	// ------------------------------------------------------------------ containers

	/** With a container open: take one stack of emerald blocks, then close it. */
	static final class TakeBlocks extends Task {
		private int delay = 8 + R.nextInt(8);
		private int taken;

		@Override
		Status tick(Minecraft mc, LocalPlayer p, ClientLevel lv) {
			if (!containerOpen(p)) return Status.FAILED;
			if (delay-- > 0) return Status.RUNNING;
			AbstractContainerMenu m = p.containerMenu;
			if (taken >= 1 || Inv.freeSlots(p) < 3) {
				p.closeContainer();
				return Status.DONE;
			}
			Slot found = null;
			for (Slot s : Inv.slots(m, p, false)) {
				if (s.getItem().is(Items.EMERALD_BLOCK)) {
					found = s;
					break;
				}
			}
			if (found == null) {
				Bot.emeraldsEmpty = Inv.count(p, Items.EMERALD_BLOCK) == 0;
				p.closeContainer();
				return Status.DONE;
			}
			Inv.click(mc, m.containerId, found.index, 0, ClickType.QUICK_MOVE);
			taken++;
			delay = 5 + R.nextInt(5);
			return Status.RUNNING;
		}
	}

	/** Crafts every emerald block in the inventory into emeralds using the 2x2 grid. */
	static final class Craft extends Task {
		private final List<Integer> slots = new ArrayList<>();
		private boolean init;
		private int step, delay = 6 + R.nextInt(6);

		@Override
		Status tick(Minecraft mc, LocalPlayer p, ClientLevel lv) {
			if (containerOpen(p) || mc.screen != null) return Status.FAILED;
			if (delay-- > 0) return Status.RUNNING;
			AbstractContainerMenu m = p.inventoryMenu;
			if (!init) {
				init = true;
				for (int i = 9; i <= 44; i++) {
					if (m.getSlot(i).getItem().is(Items.EMERALD_BLOCK)) slots.add(i);
				}
			}
			if (slots.isEmpty()) return Status.DONE;
			int slot = slots.get(0);
			switch (step) {
				case 0 -> Inv.click(mc, m.containerId, slot, 0, ClickType.PICKUP);     // pick the stack up
				case 1 -> Inv.click(mc, m.containerId, 1, 0, ClickType.PICKUP);        // drop it in the crafting grid
				case 2 -> Inv.click(mc, m.containerId, 0, 0, ClickType.QUICK_MOVE);    // take all the emeralds
				default -> {
					Inv.click(mc, m.containerId, 1, 0, ClickType.QUICK_MOVE);          // return any leftovers
					slots.remove(0);
					step = -1;
				}
			}
			step++;
			delay = 3 + R.nextInt(4);
			return Status.RUNNING;
		}
	}

	/** With a container open: move everything we don't need into it, then close it. */
	static final class Dump extends Task {
		private int delay = 8 + R.nextInt(8);
		private final Set<Integer> tried = new HashSet<>();

		@Override
		Status tick(Minecraft mc, LocalPlayer p, ClientLevel lv) {
			if (!containerOpen(p)) return Status.FAILED;
			if (delay-- > 0) return Status.RUNNING;
			AbstractContainerMenu m = p.containerMenu;
			for (Slot s : Inv.slots(m, p, true)) {
				ItemStack st = s.getItem();
				if (st.isEmpty() || Inv.keep(st) || tried.contains(s.index)) continue;
				tried.add(s.index);
				Inv.click(mc, m.containerId, s.index, 0, ClickType.QUICK_MOVE);
				delay = 2 + R.nextInt(4);
				return Status.RUNNING;
			}
			p.closeContainer();
			return Status.DONE;
		}
	}

	// ------------------------------------------------------------------ bookshelves

	/** Holds the attack key on a block until it is gone. */
	static final class Mine extends Task {
		private final BlockPos pos;
		private final Vec3 point;
		private int ticks;

		Mine(BlockPos pos) {
			this.pos = pos;
			this.point = Vec3.atCenterOf(pos).add((R.nextDouble() - 0.5) * 0.4, (R.nextDouble() - 0.5) * 0.4, (R.nextDouble() - 0.5) * 0.4);
		}

		@Override
		Status tick(Minecraft mc, LocalPlayer p, ClientLevel lv) {
			if (lv.getBlockState(pos).isAir()) {
				mc.options.keyAttack.setDown(false);
				return Status.DONE;
			}
			if (++ticks > 400) {
				mc.options.keyAttack.setDown(false);
				return Status.FAILED;
			}
			boolean aimed = Look.aimAt(p, point);
			boolean onIt = mc.hitResult instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK && b.getBlockPos().equals(pos);
			mc.options.keyAttack.setDown(aimed && onIt);
			return Status.RUNNING;
		}
	}

	/** Walks over dropped books near a point to pick them up. */
	static final class Pickup extends Task {
		private final Vec3 center;
		private Walk inner;
		private int ticks, wait = 14 + R.nextInt(10);

		Pickup(Vec3 center) {
			this.center = center;
		}

		@Override
		Status tick(Minecraft mc, LocalPlayer p, ClientLevel lv) {
			if (++ticks > 900) {
				Mover.releaseAll(mc);
				return Status.DONE;
			}
			if (wait-- > 0) {
				Mover.releaseAll(mc);
				return Status.RUNNING;
			}
			if (inner != null) {
				Status s = inner.tick(mc, p, lv);
				if (s == Status.RUNNING) return s;
				inner = null;
				wait = 6;
				return Status.RUNNING;
			}
			AABB box = new AABB(center.x - 12, center.y - 8, center.z - 12, center.x + 12, center.y + 8, center.z + 12);
			ItemEntity best = null;
			for (ItemEntity it : lv.getEntitiesOfClass(ItemEntity.class, box)) {
				if (!it.getItem().is(Items.BOOK)) continue;
				if (best == null || it.distanceToSqr(p) < best.distanceToSqr(p)) best = it;
			}
			if (best == null) {
				Mover.releaseAll(mc);
				return Status.DONE;
			}
			inner = new Walk(Goals.within(best.position(), 0.9), best.position());
			return Status.RUNNING;
		}
	}

	// ------------------------------------------------------------------ trading

	private static boolean isGlass(MerchantOffer o) {
		return o.getResult().is(Items.GLASS);
	}

	/** Opens a villager, looks for a glass trade, otherwise does the trades it can afford. */
	static final class Session extends Task {
		private enum St { OPEN, WAIT_OFFERS, DECIDE, SELECT, WAIT_RESULT, TAKE, WAIT_EMPTY, CLOSE, CLOSING }

		private final Villager villager;
		private final UseEntity use;
		private St st = St.OPEN;
		private int wait, sel, trades, ticks;
		private final Set<Integer> blocked = new HashSet<>();
		private Bot.Kind kind = Bot.Kind.EXHAUSTED;
		private int needBooks;
		private boolean needEmeralds;

		Session(Villager v) {
			this.villager = v;
			this.use = new UseEntity(v);
		}

		private void finish(Bot.Kind k) {
			kind = k;
			st = St.CLOSE;
		}

		@Override
		Status tick(Minecraft mc, LocalPlayer p, ClientLevel lv) {
			if (++ticks > 6000) return Status.FAILED;
			if (st == St.OPEN) {
				Status s = use.tick(mc, p, lv);
				if (s == Status.DONE) {
					st = St.WAIT_OFFERS;
					wait = 0;
				} else if (s == Status.FAILED) {
					if (!use.clicked) return Status.FAILED; // never managed to click it: retry instead of misfiling it
					Bot.pending = Bot.Kind.NO_TRADES; // clicked but no screen: unemployed / nitwit
					return Status.DONE;
				}
				return Status.RUNNING;
			}
			if (!(p.containerMenu instanceof MerchantMenu m)) {
				if (st == St.CLOSING) return Status.DONE;
				return Status.FAILED;
			}

			switch (st) {
				case WAIT_OFFERS -> {
					if (m.getOffers().isEmpty()) {
						if (++wait > 40) finish(Bot.Kind.NO_TRADES);
						return Status.RUNNING;
					}
					wait = 8 + R.nextInt(12); // "reading" the trades
					st = St.DECIDE;
				}
				case DECIDE -> {
					if (wait-- > 0) return Status.RUNNING;
					decide(m, p);
				}
				case SELECT -> {
					if (wait-- > 0) return Status.RUNNING;
					ClientPacketListener conn = mc.getConnection();
					if (conn == null) return Status.FAILED;
					m.setSelectionHint(sel);
					m.tryMoveItems(sel);
					conn.send(new ServerboundSelectTradePacket(sel));
					st = St.WAIT_RESULT;
					wait = 0;
				}
				case WAIT_RESULT -> {
					if (m.getSlot(2).hasItem()) {
						st = St.TAKE;
						wait = 2 + R.nextInt(4);
					} else if (++wait > 25) {
						blocked.add(sel);
						st = St.DECIDE;
						wait = 3;
					}
				}
				case TAKE -> {
					if (wait-- > 0) return Status.RUNNING;
					Inv.click(mc, m.containerId, 2, 0, ClickType.QUICK_MOVE);
					trades++;
					st = St.WAIT_EMPTY;
					wait = 0;
				}
				case WAIT_EMPTY -> {
					if (!m.getSlot(2).hasItem() || ++wait > 15) {
						st = St.DECIDE;
						wait = 4 + R.nextInt(8);
					}
				}
				case CLOSE -> {
					p.closeContainer();
					Bot.pending = kind;
					Bot.needBooks = needBooks;
					st = St.CLOSING;
					wait = 8;
					return Status.DONE;
				}
				default -> { }
			}
			return Status.RUNNING;
		}

		private void decide(MerchantMenu m, LocalPlayer p) {
			MerchantOffers offers = m.getOffers();
			for (MerchantOffer o : offers) {
				if (isGlass(o)) {
					finish(Bot.Kind.GLASS);
					return;
				}
			}
			if (Inv.freeSlots(p) < 2) {
				finish(Bot.Kind.NEED_DUMP);
				return;
			}
			if (trades >= 300) {
				finish(Bot.Kind.PROGRESS);
				return;
			}
			int best = -1;
			int bestScore = Integer.MIN_VALUE;
			for (int i = 0; i < offers.size(); i++) {
				MerchantOffer o = offers.get(i);
				if (blocked.contains(i) || o.isOutOfStock()) continue;
				ItemStack a = o.getCostA();
				ItemStack b = o.getCostB();
				int times = Integer.MAX_VALUE;
				int emeraldCost = 0;
				for (ItemStack c : new ItemStack[] {a, b}) {
					if (c.isEmpty()) continue;
					int have = Inv.count(p, c.getItem());
					times = Math.min(times, have / Math.max(1, c.getCount()));
					if (c.is(Items.EMERALD)) emeraldCost += c.getCount();
					if (have < c.getCount()) {
						if (c.is(Items.EMERALD)) needEmeralds = true;
						else if (c.is(Items.BOOK)) {
							int left = Math.max(1, o.getMaxUses() - o.getUses());
							needBooks = Math.max(needBooks, Math.min(48, c.getCount() * left));
						}
					}
				}
				if (times == Integer.MAX_VALUE || times < 1) continue;
				int score = (o.getResult().is(Items.EMERALD) ? 1000 : 0) + o.getXp() * 10 - emeraldCost;
				if (score > bestScore) {
					bestScore = score;
					best = i;
				}
			}
			if (best < 0) {
				if (trades > 0) finish(Bot.Kind.PROGRESS);       // come back after it has had time to level up
				else if (needBooks > 0) finish(Bot.Kind.NEED_BOOKS);
				else if (needEmeralds) finish(Bot.Kind.NEED_EMERALDS);
				else finish(Bot.Kind.EXHAUSTED);
				return;
			}
			sel = best;
			st = St.SELECT;
			wait = 3 + R.nextInt(5);
		}
	}

	// ------------------------------------------------------------------ pushing carts

	/** Sprints into a villager's minecart to shove it along its rails toward a spot. */
	static final class Push extends Task {
		private static final double ARRIVE = 1.6;

		private final Villager villager;
		private final Entity cart;
		private final BlockPos dest;
		private int phase, ticks, failures, wait, chargeTicks, still;
		private Walk walk;
		private Vec3 startPos;
		private int dirX, dirZ;

		Push(Villager v, BlockPos dest) {
			this.villager = v;
			this.cart = v.getVehicle();
			this.dest = dest;
		}

		private double dist() {
			Vec3 c = cart.position();
			return Math.hypot(c.x - (dest.getX() + 0.5), c.z - (dest.getZ() + 0.5));
		}

		private void chooseDir() {
			Vec3 c = cart.position();
			double dx = dest.getX() + 0.5 - c.x;
			double dz = dest.getZ() + 0.5 - c.z;
			boolean xFirst = Math.abs(dx) >= Math.abs(dz);
			double major = xFirst ? dx : dz;
			double minor = xFirst ? dz : dx;
			boolean useMinor = Math.abs(minor) >= 0.6 && (failures / 2) % 2 == 1;
			double v = useMinor ? minor : major;
			int sign = v >= 0 ? 1 : -1;
			boolean alongX = useMinor ? !xFirst : xFirst;
			dirX = alongX ? sign : 0;
			dirZ = alongX ? 0 : sign;
		}

		@Override
		Status tick(Minecraft mc, LocalPlayer p, ClientLevel lv) {
			if (++ticks > 6000 || cart == null || !cart.isAlive() || villager.getVehicle() != cart) {
				Mover.releaseAll(mc);
				return Status.FAILED;
			}
			switch (phase) {
				case 0 -> {
					if (dist() <= ARRIVE) {
						phase = 5;
						still = 0;
						return Status.RUNNING;
					}
					chooseDir();
					BlockPos cb = cart.blockPosition();
					int back = 2 + Math.min(failures, 3);
					int bx = cb.getX() - dirX * back;
					int bz = cb.getZ() - dirZ * back;
					walk = new Walk(Goals.column(bx, bz, cb.getY()), new Vec3(bx + 0.5, cb.getY(), bz + 0.5));
					phase = 1;
				}
				case 1 -> {
					Status s = walk.tick(mc, p, lv);
					if (s == Status.DONE) phase = 2;
					else if (s == Status.FAILED) {
						if (++failures > 8) return Status.FAILED;
						phase = 0;
					}
				}
				case 2 -> {
					Mover.releaseAll(mc);
					double err = Look.turn(p, Look.yawTo(dirX, dirZ), 6f);
					if (err < 4) {
						phase = 3;
						startPos = cart.position();
						chargeTicks = 0;
					}
				}
				case 3 -> {
					Look.turn(p, Look.yawTo(dirX, dirZ), 6f);
					Mover.keys(mc, true, true, false);
					chargeTicks++;
					if (cart.position().distanceTo(startPos) > 0.6 || chargeTicks > 30) {
						Mover.releaseAll(mc);
						phase = 4;
						wait = 25;
					}
				}
				case 4 -> {
					if (--wait <= 0) {
						if (cart.position().distanceTo(startPos) < 0.5) failures++;
						else failures = 0;
						if (failures > 8) return Status.FAILED;
						phase = 0;
					}
				}
				default -> {
					Mover.releaseAll(mc);
					if (dist() > ARRIVE + 1.0) {
						phase = 0;
					} else if (cart.getDeltaMovement().horizontalDistanceSqr() < 1.0E-4) {
						if (++still > 15) return Status.DONE;
					} else {
						still = 0;
					}
				}
			}
			return Status.RUNNING;
		}
	}
}
