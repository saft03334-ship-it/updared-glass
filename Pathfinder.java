package com.example.glassbot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Walking pathfinder over the blocks the client has loaded: flat, step up 1, drop up to 3. */
final class Pathfinder {
	private static final class N {
		final BlockPos pos;
		final double g;
		final double f;
		final N parent;

		N(BlockPos pos, double g, double f, N parent) {
			this.pos = pos;
			this.g = g;
			this.f = f;
			this.parent = parent;
		}
	}

	private record Step(BlockPos pos, double cost) {}

	private static final Direction[] DIRS = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

	private Pathfinder() {}

	static boolean passable(ClientLevel lv, BlockPos p) {
		BlockState s = lv.getBlockState(p);
		if (!s.getFluidState().isEmpty()) return false;
		if (!s.getCollisionShape(lv, p).isEmpty()) return false;
		return !(s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE) || s.is(Blocks.COBWEB)
				|| s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.WITHER_ROSE));
	}

	static boolean floor(ClientLevel lv, BlockPos p) {
		BlockState s = lv.getBlockState(p);
		if (s.is(Blocks.MAGMA_BLOCK) || s.is(Blocks.CACTUS)) return false;
		return s.isFaceSturdy(lv, p, Direction.UP);
	}

	private static List<Step> steps(ClientLevel lv, BlockPos p) {
		List<Step> out = new ArrayList<>(8);
		for (Direction d : DIRS) {
			BlockPos fwd = p.relative(d);
			BlockPos fwdUp = fwd.above();
			if (passable(lv, fwd) && passable(lv, fwdUp)) {
				if (floor(lv, fwd.below())) {
					out.add(new Step(fwd, 1.0));
				} else {
					for (int k = 1; k <= 3; k++) {
						BlockPos q = fwd.below(k);
						if (!passable(lv, q)) break;
						if (floor(lv, q.below())) {
							out.add(new Step(q, 1.5 + 0.5 * k));
							break;
						}
					}
				}
			} else if (!passable(lv, fwd)) {
				if (passable(lv, p.above(2)) && passable(lv, fwdUp) && passable(lv, fwdUp.above()) && floor(lv, fwd)) {
					out.add(new Step(fwdUp, 1.8));
				}
			}
		}
		return out;
	}

	private static double h(BlockPos p, Vec3 hint) {
		if (hint == null) return 0;
		double d = Math.hypot(p.getX() + 0.5 - hint.x, p.getZ() + 0.5 - hint.z);
		return Math.max(0, d - 3.0);
	}

	/** Returns the cells to walk through (start excluded), or null if no goal could be reached. */
	static List<BlockPos> find(ClientLevel lv, BlockPos start, Predicate<BlockPos> goal, Vec3 hint, Set<Long> avoid, int maxExpand) {
		PriorityQueue<N> open = new PriorityQueue<>(Comparator.comparingDouble((N n) -> n.f));
		Map<Long, Double> best = new HashMap<>();
		Set<Long> closed = new HashSet<>();
		open.add(new N(start, 0, h(start, hint), null));
		best.put(start.asLong(), 0.0);
		int expanded = 0;

		while (!open.isEmpty() && expanded < maxExpand) {
			N n = open.poll();
			if (!closed.add(n.pos.asLong())) continue;
			expanded++;
			if (goal.test(n.pos)) {
				List<BlockPos> path = new ArrayList<>();
				for (N c = n; c.parent != null; c = c.parent) path.add(c.pos);
				Collections.reverse(path);
				return path;
			}
			for (Step s : steps(lv, n.pos)) {
				long key = s.pos().asLong();
				if (closed.contains(key)) continue;
				double g = n.g + s.cost() + (avoid != null && avoid.contains(key) ? 40.0 : 0.0);
				Double old = best.get(key);
				if (old != null && old <= g) continue;
				best.put(key, g);
				open.add(new N(s.pos(), g, g + h(s.pos(), hint), n));
			}
		}
		return null;
	}
}
