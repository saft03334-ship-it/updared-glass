package com.example.glassbot;

import java.util.List;
import java.util.Random;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Follows a path by pressing the real movement keys, with human-ish turning, pauses and sprinting. */
final class Mover {
	enum S { RUNNING, DONE, STUCK }

	private static final Random R = new Random();

	private List<BlockPos> path;
	private int idx;
	private Vec3 mark;
	private int markTick;
	private int tick;
	private int stuck;
	private int pause;

	void setPath(List<BlockPos> p) {
		path = p;
		idx = 0;
		stuck = 0;
		mark = null;
		tick = 0;
		pause = 0;
	}

	static void releaseAll(Minecraft mc) {
		mc.options.keyUp.setDown(false);
		mc.options.keyDown.setDown(false);
		mc.options.keyLeft.setDown(false);
		mc.options.keyRight.setDown(false);
		mc.options.keyJump.setDown(false);
		mc.options.keySprint.setDown(false);
		mc.options.keyUse.setDown(false);
		mc.options.keyAttack.setDown(false);
	}

	static void keys(Minecraft mc, boolean forward, boolean sprint, boolean jump) {
		mc.options.keyUp.setDown(forward);
		mc.options.keySprint.setDown(sprint);
		mc.options.keyJump.setDown(jump);
		mc.options.keyDown.setDown(false);
		mc.options.keyLeft.setDown(false);
		mc.options.keyRight.setDown(false);
	}

	S tick(Minecraft mc, LocalPlayer p) {
		if (path == null || idx >= path.size()) {
			releaseAll(mc);
			return S.DONE;
		}
		tick++;
		Vec3 pos = p.position();
		BlockPos node = path.get(idx);
		double dx = node.getX() + 0.5 - pos.x;
		double dz = node.getZ() + 0.5 - pos.z;
		boolean last = idx == path.size() - 1;

		if (Math.hypot(dx, dz) < (last ? 0.3 : 0.45) && Math.abs(pos.y - node.getY()) < 1.4) {
			idx++;
			if (idx >= path.size()) {
				releaseAll(mc);
				return S.DONE;
			}
			node = path.get(idx);
			dx = node.getX() + 0.5 - pos.x;
			dz = node.getZ() + 0.5 - pos.z;
			last = idx == path.size() - 1;
		}

		double yawErr = Look.turn(p, Look.yawTo(dx, dz), 8f + (float) (R.nextGaussian() * 1.5));
		boolean forward = yawErr < 35;

		if (pause > 0) {
			pause--;
			keys(mc, false, false, false);
			return S.RUNNING;
		}
		if (R.nextInt(260) == 0) pause = 3 + R.nextInt(8); // the odd hesitation

		boolean sprint = forward && yawErr < 10 && path.size() - idx >= 3 && p.getFoodData().getFoodLevel() > 6;
		boolean jump = p.onGround() && (node.getY() > p.blockPosition().getY() || (p.horizontalCollision && forward));
		keys(mc, forward, sprint, jump);

		if (mark == null || tick - markTick >= 20) {
			if (mark != null) {
				if (pos.distanceTo(mark) < 0.25 && forward) {
					if (++stuck >= 3) {
						releaseAll(mc);
						return S.STUCK;
					}
				} else if (pos.distanceTo(mark) >= 0.25) {
					stuck = 0;
				}
			}
			mark = pos;
			markTick = tick;
		}
		return S.RUNNING;
	}
}
