package com.yumatan.cdp;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(ColonistDesirePaths.MODID)
public class ColonistDesirePaths {

    public static final String MODID = "colonist_desire_paths";
    static final Logger LOGGER = LogUtils.getLogger();

    public ColonistDesirePaths(IEventBus modEventBus, ModContainer modContainer) {
        NeoForge.EVENT_BUS.addListener(PathConversionHandler::onEntityTick);
        LOGGER.info("[ColonistDesirePaths] Loaded. Colonists will now wear desire paths into the ground.");
    }
}
