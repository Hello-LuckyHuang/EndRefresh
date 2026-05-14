package com.helloluckyhuang.endrefresh.event;

import com.helloluckyhuang.endrefresh.EndChunkCleaner;
import com.helloluckyhuang.endrefresh.EndRefresh;
import net.minecraft.commands.Commands;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@EventBusSubscriber(modid = EndRefresh.MODID)
public class Command {
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher()
                .register(
                        Commands.literal("endrefresh")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.literal("cleanup").executes(context -> EndChunkCleaner.startManualCleanup(context.getSource())))
                );
    }
}
