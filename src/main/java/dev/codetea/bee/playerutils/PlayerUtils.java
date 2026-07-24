package dev.codetea.bee.playerutils;

import dev.codetea.bee.playerutils.command.CommandHandler;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

@Mod(PlayerUtils.MODID)
public class PlayerUtils {
    public static final String MODID = "playerutils";

    public PlayerUtils(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.register(CommandHandler.class);
    }
}