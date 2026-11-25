package com.example.glassbot;

import java.util.function.Predicate;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** "Where may I stand?" predicates for the pathfinder. */
final class Goals {
	private Goals() {}

	/** A cell from which the block is within reach and in clear view. */
	static Predicate<BlockPos> nearBlock(BlockPos target) {
		Vec3 center = Vec3.atCenterOf(target);
		return c -> {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player == null || mc.level == null) return false;
			Vec3 eye = new Vec3(c.getX() + 0.5, c.getY() + 1.62, c.getZ() + 0.5);
			double hd = Math.hypot(c.getX() + 0.5 - center.x, c.getZ() + 0.5 - center.z);
			if (hd < 0.9 || eye.distanceTo(center) > 3.6) return false;
			BlockHitResult r = mc.level.clip(new ClipContext(eye, center, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
			return r.getType() == HitResult.Type.BLOCK && r.getBlockPos().equals(target);
		};
	}

	/** A cell from which the entity is within reach with nothing solid in between. */
	static Predicate<BlockPos> nearEntity(Entity e) {
		return c -> {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player == null || mc.level == null) return false;
			Vec3 eye = new Vec3(c.getX() + 0.5, c.getY() + 1.62, c.getZ() + 0.5);
			Vec3 t = e.getEyePosition();
			double d = eye.distanceTo(t);
			if (d > 2.8 || d < 1.0) return false;
			BlockHitResult r = mc.level.clip(new ClipContext(eye, t, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
			return r.getType() == HitResult.Type.MISS;
		};
	}

	/** Any cell in the given block column, within one block of the given height. */
	static Predicate<BlockPos> column(int x, int z, int y) {
		return c -> c.getX() == x && c.getZ() == z && Math.abs(c.getY() - y) <= 1;
	}

	/** Any cell horizontally within r of a point, within 2 blocks vertically. */
	static Predicate<BlockPos> within(Vec3 p, double r) {
		return c -> Math.hypot(c.getX() + 0.5 - p.x, c.getZ() + 0.5 - p.z) <= r && Math.abs(c.getY() - p.y) <= 2;
	}
}
