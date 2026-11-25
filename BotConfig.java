package com.example.glassbot;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;

/** The designated spots, saved to config/glassbot.json. */
public final class BotConfig {
	public enum Spot {
		EMERALD("emerald", "Emerald container"),
		DUMP("dump", "Dump container"),
		BOOKS("books", "Bookshelf area"),
		QUEUE("queue", "Villager queue"),
		GLASS("glass", "Glass villager drop-off"),
		NONGLASS("nonglass", "Non-glass villager drop-off");

		public final String id;
		public final String label;

		Spot(String id, String label) {
			this.id = id;
			this.label = label;
		}
	}

	private static class Data {
		Map<String, int[]> spots = new HashMap<>();
		int queueRadius = 8;
		int bookRadius = 6;
	}

	private static Data data = new Data();

	private BotConfig() {}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("glassbot.json");
	}

	public static void load() {
		try {
			Path f = file();
			if (Files.exists(f)) {
				try (Reader r = Files.newBufferedReader(f)) {
					Data d = new Gson().fromJson(r, Data.class);
					if (d != null) data = d;
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public static void save() {
		try (Writer w = Files.newBufferedWriter(file())) {
			new GsonBuilder().setPrettyPrinting().create().toJson(data, w);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public static BlockPos get(Spot s) {
		int[] a = data.spots.get(s.id);
		return a == null || a.length < 3 ? null : new BlockPos(a[0], a[1], a[2]);
	}

	public static void set(Spot s, BlockPos p) {
		data.spots.put(s.id, new int[] {p.getX(), p.getY(), p.getZ()});
		save();
	}

	public static int queueRadius() { return data.queueRadius; }
	public static int bookRadius() { return data.bookRadius; }
	public static void queueRadius(int r) { data.queueRadius = r; save(); }
	public static void bookRadius(int r) { data.bookRadius = r; save(); }
}
