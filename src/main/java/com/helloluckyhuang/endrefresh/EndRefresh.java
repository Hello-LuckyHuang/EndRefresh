package com.helloluckyhuang.endrefresh;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(EndRefresh.MODID)
public class EndRefresh {
    public static final String MODID = "endrefresh";
    public static final Logger LOGGER = LogUtils.getLogger();

    public EndRefresh(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        NeoForge.EVENT_BUS.register(EndChunkCleaner.class);
    }
}
