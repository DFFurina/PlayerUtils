package dev.codetea.bee.playerutils.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.util.*;

public class RequirementParser {

    public static Map<Integer, SlotRequirement> parse(String jsonString) throws IllegalArgumentException {
        Map<Integer, SlotRequirement> result = new HashMap<>();
        try {
            JsonArray root = JsonParser.parseString(jsonString).getAsJsonArray();
            for (JsonElement elem : root) {
                JsonObject slotObj = elem.getAsJsonObject();
                for (Map.Entry<String, JsonElement> entry : slotObj.entrySet()) {
                    String slotKey = entry.getKey();
                    int slotIndex = parseSlot(slotKey);
                    JsonElement value = entry.getValue();
                    List<SlotRequirement.ItemMatcher> matchers = parseItemMatchers(value);
                    result.put(slotIndex, new SlotRequirement(slotIndex, matchers));
                }
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid slotList format: " + e.getMessage(), e);
        }
        return result;
    }

    private static int parseSlot(String slotKey) {
        if (slotKey.startsWith("hotbar.")) {
            int idx = Integer.parseInt(slotKey.substring(7));
            if (idx < 0 || idx > 8) throw new IllegalArgumentException("hotbar index out of range 0-8");
            return idx;
        } else if (slotKey.startsWith("inventory.")) {
            int idx = Integer.parseInt(slotKey.substring(10));
            if (idx < 9 || idx > 35) throw new IllegalArgumentException("inventory index must be 9-35");
            return idx;
        } else if (slotKey.equals("armor.head")) {
            return 39;
        } else if (slotKey.equals("armor.chest")) {
            return 38;
        } else if (slotKey.equals("armor.legs")) {
            return 37;
        } else if (slotKey.equals("armor.feet")) {
            return 36;
        } else if (slotKey.equals("offhand")) {
            return 40;
        } else {
            throw new IllegalArgumentException("Unknown slot: " + slotKey);
        }
    }

    private static List<SlotRequirement.ItemMatcher> parseItemMatchers(JsonElement value) {
        List<SlotRequirement.ItemMatcher> matchers = new ArrayList<>();
        if (value.isJsonObject()) {
            JsonObject obj = value.getAsJsonObject();
            for (Map.Entry<String, JsonElement> itemEntry : obj.entrySet()) {
                String itemId = itemEntry.getKey();
                ResourceLocation location = ResourceLocation.tryParse(itemId);
                if (location == null) throw new IllegalArgumentException("Invalid item id: " + itemId);
                Item item = BuiltInRegistries.ITEM.get(location);
                if (item == null) throw new IllegalArgumentException("Unknown item: " + itemId);
                int count = -1;
                JsonElement countElem = itemEntry.getValue();
                if (countElem.isJsonPrimitive() && countElem.getAsJsonPrimitive().isNumber()) {
                    count = countElem.getAsInt();
                }
                matchers.add(new SlotRequirement.ItemMatcher(item, count));
            }
        } else {
            throw new IllegalArgumentException("Slot value must be an object");
        }
        return matchers;
    }
}