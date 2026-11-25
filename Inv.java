package com.example.glassbot;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

final class Inv {
	private Inv() {}

	static int count(LocalPlayer p, Item item) {
		int n = 0;
		for (int i = 0; i < 36; i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (s.is(item)) n += s.getCount();
		}
		return n;
	}

	static int freeSlots(LocalPlayer p) {
		int n = 0;
		for (int i = 0; i < 36; i++) if (p.getInventory().getItem(i).isEmpty()) n++;
		return n;
	}

	/** Things we never dump: emeralds, the items villagers want for leveling, and tools/armor. */
	static boolean keep(ItemStack s) {
		return s.is(Items.EMERALD) || s.is(Items.EMERALD_BLOCK) || s.is(Items.BOOK)
				|| s.is(Items.PAPER) || s.is(Items.INK_SAC) || s.isDamageableItem();
	}

	static int junkStacks(LocalPlayer p) {
		int n = 0;
		for (int i = 0; i < 36; i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (!s.isEmpty() && !keep(s)) n++;
		}
		return n;
	}

	/** Slots of an open menu: the player's own 36 slots (playerSide) or the container's slots. */
	static List<Slot> slots(AbstractContainerMenu m, LocalPlayer p, boolean playerSide) {
		List<Slot> out = new ArrayList<>();
		for (Slot s : m.slots) {
			if ((s.container == p.getInventory()) == playerSide) out.add(s);
		}
		return out;
	}

	static void click(Minecraft mc, int containerId, int slot, int button, ClickType type) {
		mc.gameMode.handleInventoryMouseClick(containerId, slot, button, type, mc.player);
	}
}
