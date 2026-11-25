package com.example.glassbot;

import java.util.Random;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Smooth, slightly noisy camera turning. */
final class Look {
	static final Random R = new Random();

	private Look() {}

	static float yawTo(double dx, double dz) {
		return (float) (Mth.atan2(dz, dx) * 180.0 / Math.PI) - 90f;
	}

	static float pitchTo(Vec3 d) {
		return (float) (-(Mth.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * 180.0 / Math.PI));
	}

	/** Eases toward the given angles; returns the error (degrees) before this step. */
	static double turn(LocalPlayer p, float wantYaw, float wantPitch) {
		float dy = Mth.wrapDegrees(wantYaw - p.getYRot());
		float dp = wantPitch - p.getXRot();
		double err = Math.hypot(dy, dp);
		if (err > 0.8) {
			float f = 0.2f + R.nextFloat() * 0.22f;
			float sy = Mth.clamp(dy * f + (float) (R.nextGaussian() * 0.35), -28f, 28f);
			float sp = Mth.clamp(dp * f + (float) (R.nextGaussian() * 0.25), -18f, 18f);
			p.setYRot(p.getYRot() + sy);
			p.setXRot(Mth.clamp(p.getXRot() + sp, -90f, 90f));
		}
		return err;
	}

	/** Aim the crosshair at a point; true once it is lined up. */
	static boolean aimAt(LocalPlayer p, Vec3 point) {
		Vec3 d = point.subtract(p.getEyePosition());
		return turn(p, yawTo(d.x, d.z), pitchTo(d)) < 1.6;
	}
}
