package dev.codetea.bee.playerutils.command;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public class SlotRequirement {
    private final int slotIndex;
    private final List<ItemMatcher> matchers;

    public SlotRequirement(int slotIndex, List<ItemMatcher> matchers) {
        this.slotIndex = slotIndex;
        this.matchers = matchers;
    }

    public int getSlotIndex() { return slotIndex; }

    public boolean matches(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        for (ItemMatcher matcher : matchers) {
            if (matcher.matches(stack)) {
                return true;
            }
        }
        return false;
    }

    public static class ItemMatcher {
        private final Item item;
        private final int count;

        public ItemMatcher(Item item, int count) {
            this.item = item;
            this.count = count;
        }

        public boolean matches(ItemStack stack) {
            if (!stack.getItem().equals(item)) return false;
            if (count == -1) return true;
            return stack.getCount() >= count;
        }
    }
}