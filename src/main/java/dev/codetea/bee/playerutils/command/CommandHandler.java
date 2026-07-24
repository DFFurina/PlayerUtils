package dev.codetea.bee.playerutils.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CommandHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(CommandHandler.class);

    private static final Map<Integer, SlotRequirement> UNCONDITIONAL = Collections.emptyMap();

    private static final String KEY_CHOKE_JSON = "playerutils:choke_json";
    private static final String KEY_HEALTH_DATA = "playerutils:health_data";
    private static final String KEY_PVP_MODE = "playerutils:pvp_mode";
    private static final String KEY_AUTOSP = "playerutils:autosp";

    private static final Map<UUID, Map<Integer, SlotRequirement>> chokingPlayers = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> currentAirMap = new ConcurrentHashMap<>();

    private static final Map<UUID, HealthData> healthPlayers = new ConcurrentHashMap<>();

    private static final Map<UUID, Boolean> pvpDisabled = new ConcurrentHashMap<>();

    private static final Map<UUID, Boolean> autoSpawnPlayers = new ConcurrentHashMap<>();

    private static class HealthData {
        int targetHealth;
        Map<Integer, SlotRequirement> requirements;
        HealthData(int health, Map<Integer, SlotRequirement> req) {
            this.targetHealth = health;
            this.requirements = req;
        }
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(
            Commands.literal("choke")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("start")
                    .then(Commands.argument("targets", EntityArgument.players())
                        .then(Commands.argument("slotList", StringArgumentType.greedyString())
                            .executes(ctx -> executeChokeStart(ctx, EntityArgument.getPlayers(ctx, "targets"), StringArgumentType.getString(ctx, "slotList")))
                        )
                        .executes(ctx -> executeChokeStart(ctx, EntityArgument.getPlayers(ctx, "targets"), null))
                    )
                )
                .then(Commands.literal("stop")
                    .executes(ctx -> executeChokeStopSelf(ctx))
                    .then(Commands.argument("targets", EntityArgument.players())
                        .executes(ctx -> executeChokeStop(ctx, EntityArgument.getPlayers(ctx, "targets")))
                    )
                )
        );

        dispatcher.register(
            Commands.literal("health")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("start")
                    .then(Commands.argument("target", EntityArgument.player())
                        .then(Commands.argument("health", IntegerArgumentType.integer(1, 100))
                            .then(Commands.argument("slotList", StringArgumentType.greedyString())
                                .executes(ctx -> executeHealthStart(ctx, EntityArgument.getPlayer(ctx, "target"), IntegerArgumentType.getInteger(ctx, "health"), StringArgumentType.getString(ctx, "slotList")))
                            )
                            .executes(ctx -> executeHealthStart(ctx, EntityArgument.getPlayer(ctx, "target"), IntegerArgumentType.getInteger(ctx, "health"), null))
                        )
                    )
                )
                .then(Commands.literal("stop")
                    .executes(ctx -> executeHealthStopSelf(ctx))
                    .then(Commands.argument("target", EntityArgument.player())
                        .executes(ctx -> executeHealthStop(ctx, EntityArgument.getPlayer(ctx, "target")))
                    )
                )
        );

        dispatcher.register(
            Commands.literal("pvp")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("start")
                    .then(Commands.argument("targets", EntityArgument.players())
                        .executes(ctx -> executePvpStart(ctx, EntityArgument.getPlayers(ctx, "targets")))
                    )
                )
                .then(Commands.literal("stop")
                    .executes(ctx -> executePvpStopSelf(ctx))
                    .then(Commands.argument("targets", EntityArgument.players())
                        .executes(ctx -> executePvpStop(ctx, EntityArgument.getPlayers(ctx, "targets")))
                    )
                )
        );

        dispatcher.register(
            Commands.literal("autosp")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("start")
                    .then(Commands.argument("targets", EntityArgument.players())
                        .executes(ctx -> executeAutospStart(ctx, EntityArgument.getPlayers(ctx, "targets")))
                    )
                )
                .then(Commands.literal("stop")
                    .executes(ctx -> executeAutospStopSelf(ctx))
                    .then(Commands.argument("targets", EntityArgument.players())
                        .executes(ctx -> executeAutospStop(ctx, EntityArgument.getPlayers(ctx, "targets")))
                    )
                )
        );
    }

    private static int executeChokeStart(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> targets, String slotListJson) {
        for (ServerPlayer player : targets) {
            Map<Integer, SlotRequirement> reqMap = parseSlotList(slotListJson);
            UUID uuid = player.getUUID();
            if (reqMap == UNCONDITIONAL) {
                chokingPlayers.put(uuid, UNCONDITIONAL);
                currentAirMap.put(uuid, player.getMaxAirSupply());
                saveChokeData(player, null);
                LOGGER.info("Started unconditional choking for {}", player.getName().getString());
            } else {
                chokingPlayers.put(uuid, reqMap);
                currentAirMap.put(uuid, player.getMaxAirSupply());
                saveChokeData(player, slotListJson);
                LOGGER.info("Started choking for {} with requirements: {}", player.getName().getString(), reqMap);
            }
        }
        return targets.size();
    }

    private static int executeChokeStopSelf(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        stopChoking(player);
        return 1;
    }

    private static int executeChokeStop(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> targets) {
        for (ServerPlayer player : targets) {
            stopChoking(player);
        }
        return targets.size();
    }

    private static void stopChoking(ServerPlayer player) {
        UUID uuid = player.getUUID();
        chokingPlayers.remove(uuid);
        currentAirMap.remove(uuid);
        player.setAirSupply(player.getMaxAirSupply());
        clearChokeData(player);
        LOGGER.info("Stopped choking for: {}", player.getName().getString());
    }

    private static int executeHealthStart(CommandContext<CommandSourceStack> ctx, ServerPlayer target, int health, String slotListJson) {
        Map<Integer, SlotRequirement> reqMap = parseSlotList(slotListJson);
        if (reqMap == UNCONDITIONAL) {
            double newMax = Math.max(health, 1.0);
            target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(newMax);
            target.setHealth(health);
            healthPlayers.put(target.getUUID(), new HealthData(health, UNCONDITIONAL));
            saveHealthData(target, health, null);
            LOGGER.info("Started unconditional health locking for {} to {}", target.getName().getString(), health);
        } else {
            double newMax = Math.max(health, 1.0);
            target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(newMax);
            target.setHealth(health);
            healthPlayers.put(target.getUUID(), new HealthData(health, reqMap));
            saveHealthData(target, health, slotListJson);
            LOGGER.info("Started health locking for {} to {} with requirements: {}", target.getName().getString(), health, reqMap);
        }
        return 1;
    }

    private static int executeHealthStopSelf(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        stopHealthLocking(player);
        return 1;
    }

    private static int executeHealthStop(CommandContext<CommandSourceStack> ctx, ServerPlayer target) {
        stopHealthLocking(target);
        return 1;
    }

    private static void stopHealthLocking(ServerPlayer player) {
        player.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20.0);
        if (player.getHealth() > 20) {
            player.setHealth(20);
        }
        healthPlayers.remove(player.getUUID());
        clearHealthData(player);
        LOGGER.info("Stopped health locking for: {}", player.getName().getString());
    }

    private static int executePvpStart(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> targets) {
        for (ServerPlayer player : targets) {
            UUID uuid = player.getUUID();
            pvpDisabled.put(uuid, true);
            savePvpMode(player, true);
            LOGGER.info("Disabled PVP for {}", player.getName().getString());
        }
        return targets.size();
    }

    private static int executePvpStopSelf(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        stopPvp(player);
        return 1;
    }

    private static int executePvpStop(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> targets) {
        for (ServerPlayer player : targets) {
            stopPvp(player);
        }
        return targets.size();
    }

    private static void stopPvp(ServerPlayer player) {
        pvpDisabled.remove(player.getUUID());
        clearPvpMode(player);
        LOGGER.info("Enabled PVP for {}", player.getName().getString());
    }

    private static int executeAutospStart(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> targets) {
        for (ServerPlayer player : targets) {
            UUID uuid = player.getUUID();
            autoSpawnPlayers.put(uuid, true);
            saveAutosp(player, true);
            LOGGER.info("Enabled auto respawn for {}", player.getName().getString());
        }
        return targets.size();
    }

    private static int executeAutospStopSelf(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        stopAutosp(player);
        return 1;
    }

    private static int executeAutospStop(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> targets) {
        for (ServerPlayer player : targets) {
            stopAutosp(player);
        }
        return targets.size();
    }

    private static void stopAutosp(ServerPlayer player) {
        autoSpawnPlayers.remove(player.getUUID());
        saveAutosp(player, false);
        LOGGER.info("Disabled auto respawn for {}", player.getName().getString());
    }

    private static void saveChokeData(Player player, String json) {
        CompoundTag tag = player.getPersistentData();
        if (json != null && !json.isEmpty()) {
            tag.putString(KEY_CHOKE_JSON, json);
        } else {
            tag.remove(KEY_CHOKE_JSON);
        }
    }

    private static void clearChokeData(Player player) {
        player.getPersistentData().remove(KEY_CHOKE_JSON);
    }

    private static void saveHealthData(Player player, int health, String json) {
        CompoundTag tag = player.getPersistentData();
        CompoundTag healthTag = new CompoundTag();
        healthTag.putInt("health", health);
        healthTag.putString("requirements", json != null ? json : "");
        tag.put(KEY_HEALTH_DATA, healthTag);
    }

    private static void clearHealthData(Player player) {
        player.getPersistentData().remove(KEY_HEALTH_DATA);
    }

    private static void savePvpMode(Player player, boolean disabled) {
        CompoundTag tag = player.getPersistentData();
        tag.putBoolean(KEY_PVP_MODE, disabled);
    }

    private static void clearPvpMode(Player player) {
        player.getPersistentData().remove(KEY_PVP_MODE);
    }

    private static void saveAutosp(Player player, boolean enabled) {
        CompoundTag tag = player.getPersistentData();
        tag.putBoolean(KEY_AUTOSP, enabled);
    }

    private static void clearAutosp(Player player) {
        player.getPersistentData().remove(KEY_AUTOSP);
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        Player player = event.getEntity();
        if (player.level().isClientSide) return;
        loadPlayerData(player);
    }

    private static void loadPlayerData(Player player) {
        UUID uuid = player.getUUID();
        chokingPlayers.remove(uuid);
        currentAirMap.remove(uuid);
        healthPlayers.remove(uuid);
        pvpDisabled.remove(uuid);
        autoSpawnPlayers.remove(uuid);

        CompoundTag tag = player.getPersistentData();

        if (tag.contains(KEY_CHOKE_JSON)) {
            String json = tag.getString(KEY_CHOKE_JSON);
            if (json != null && !json.isEmpty()) {
                Map<Integer, SlotRequirement> reqMap = parseSlotList(json);
                if (reqMap == UNCONDITIONAL || reqMap.isEmpty()) {
                    chokingPlayers.put(uuid, UNCONDITIONAL);
                    currentAirMap.put(uuid, player.getMaxAirSupply());
                    LOGGER.info("Loaded unconditional choking for {}", player.getName().getString());
                } else {
                    chokingPlayers.put(uuid, reqMap);
                    currentAirMap.put(uuid, player.getMaxAirSupply());
                    LOGGER.info("Loaded choking for {}", player.getName().getString());
                }
            }
        }

        if (tag.contains(KEY_HEALTH_DATA)) {
            CompoundTag healthTag = tag.getCompound(KEY_HEALTH_DATA);
            int health = healthTag.getInt("health");
            String json = healthTag.getString("requirements");
            Map<Integer, SlotRequirement> reqMap = parseSlotList(json);
            double newMax = Math.max(health, 1.0);
            player.getAttribute(Attributes.MAX_HEALTH).setBaseValue(newMax);
            player.setHealth(Math.min(health, (float) newMax));
            healthPlayers.put(uuid, new HealthData(health, reqMap));
            LOGGER.info("Loaded health locking for {} (target={})", player.getName().getString(), health);
        }

        if (tag.contains(KEY_PVP_MODE)) {
            boolean disabled = tag.getBoolean(KEY_PVP_MODE);
            if (disabled) {
                pvpDisabled.put(uuid, true);
                LOGGER.info("Loaded PVP disabled for {}", player.getName().getString());
            }
        }

        if (tag.contains(KEY_AUTOSP)) {
            boolean enabled = tag.getBoolean(KEY_AUTOSP);
            if (enabled) {
                autoSpawnPlayers.put(uuid, true);
                LOGGER.info("Loaded auto respawn for {}", player.getName().getString());
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = (Player) event.getEntity();
        if (player.level().isClientSide) return;
        UUID uuid = player.getUUID();

        Map<Integer, SlotRequirement> chokeReq = chokingPlayers.get(uuid);
        if (chokeReq != null) {
            if (chokeReq == UNCONDITIONAL) {
                applyChoke(player, uuid);
            } else {
                boolean match = checkRequirements(player, chokeReq);
                if (!match) {
                    applyChoke(player, uuid);
                } else {
                    if (currentAirMap.containsKey(uuid)) {
                        currentAirMap.remove(uuid);
                        player.setAirSupply(player.getMaxAirSupply());
                    }
                }
            }
        }

        HealthData healthData = healthPlayers.get(uuid);
        if (healthData != null) {
            Map<Integer, SlotRequirement> reqMap = healthData.requirements;
            boolean match;
            if (reqMap == UNCONDITIONAL || reqMap.isEmpty()) {
                match = false;
            } else {
                match = checkRequirements(player, reqMap);
            }
            if (!match) {
                float target = healthData.targetHealth;
                if (player.getMaxHealth() < target) {
                    player.getAttribute(Attributes.MAX_HEALTH).setBaseValue(target);
                }
                if (player.getHealth() != target) {
                    player.setHealth(target);
                }
            }
        }
    }

    private static void applyChoke(Player player, UUID uuid) {
        Integer currentAir = currentAirMap.get(uuid);
        if (currentAir == null) {
            currentAir = player.getMaxAirSupply();
            currentAirMap.put(uuid, currentAir);
        }
        if (currentAir > 0) {
            int newAir = currentAir - 3;
            if (newAir < 0) newAir = 0;
            currentAirMap.put(uuid, newAir);
            player.setAirSupply(newAir);
        } else {
            player.setAirSupply(0);
            if (player.tickCount % 5 == 0) {
                player.hurt(player.damageSources().drown(), 3.0f);
            }
        }
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            UUID uuid = player.getUUID();
            if (autoSpawnPlayers.containsKey(uuid) && autoSpawnPlayers.get(uuid)) {
                BlockPos pos = player.blockPosition();
                player.setRespawnPosition(player.level().dimension(), pos, 0.0f, true, false);
                LOGGER.info("Set respawn position for {} to death location: {}", player.getName().getString(), pos);
            }
        }
    }

    @SubscribeEvent
    public static void onAttackEntity(AttackEntityEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer attacker)) return;
        UUID uuid = attacker.getUUID();
        if (pvpDisabled.containsKey(uuid) && pvpDisabled.get(uuid)) {
            event.setCanceled(true);
            attacker.sendSystemMessage(Component.literal("§cYour PVP is disabled, you cannot attack other players."));
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID uuid = event.getEntity().getUUID();
        chokingPlayers.remove(uuid);
        currentAirMap.remove(uuid);
        healthPlayers.remove(uuid);
        pvpDisabled.remove(uuid);
        autoSpawnPlayers.remove(uuid);
        LOGGER.info("Cleared runtime states for {}", event.getEntity().getName().getString());
    }

    private static boolean checkRequirements(Player player, Map<Integer, SlotRequirement> reqMap) {
        if (reqMap == null || reqMap.isEmpty()) return false;
        for (Map.Entry<Integer, SlotRequirement> entry : reqMap.entrySet()) {
            int slot = entry.getKey();
            SlotRequirement req = entry.getValue();
            ItemStack stack = player.getInventory().getItem(slot);
            if (!req.matches(stack)) {
                return false;
            }
        }
        return true;
    }

    private static Map<Integer, SlotRequirement> parseSlotList(String json) {
        if (json == null || json.trim().isEmpty()) return UNCONDITIONAL;
        try {
            Map<Integer, SlotRequirement> result = RequirementParser.parse(json);
            return result.isEmpty() ? UNCONDITIONAL : result;
        } catch (Exception e) {
            LOGGER.warn("Failed to parse slotList: {}", json, e);
            return UNCONDITIONAL;
        }
    }
}