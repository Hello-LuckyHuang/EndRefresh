package com.helloluckyhuang.endrefresh;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

@EventBusSubscriber(modid = EndRefresh.MODID)
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.BooleanValue ENABLED = BUILDER
        .comment("Whether EndRefresh should periodically remove far End chunks.")
        .define("enabled", true);

    private static final ModConfigSpec.IntValue CLEANUP_INTERVAL_DAYS = BUILDER
        .comment("Real-world days between cleanup runs. This uses system time, not game time.")
        .defineInRange("cleanupIntervalDays", 1, 1, Integer.MAX_VALUE);

    private static final ModConfigSpec.IntValue KEPT_CENTER_RADIUS_CHUNKS = BUILDER
        .comment("End chunks with both x and z inside [-radius, radius] are always kept.")
        .defineInRange("keptCenterRadiusChunks", 30, 0, 30_000);

    private static final ModConfigSpec.IntValue PLAYER_PROTECTION_RADIUS_CHUNKS = BUILDER
        .comment("Chunks within this square radius of any player in The End are not deleted.")
        .defineInRange("playerProtectionRadiusChunks", 8, 0, 512);

    private static final ModConfigSpec.IntValue CHUNKS_PER_TICK = BUILDER
        .comment("Maximum chunk delete operations scheduled per server tick during a cleanup run.")
        .defineInRange("chunksPerTick", 128, 1, 4096);

    private static final ModConfigSpec.BooleanValue TIME_BASED_GENERATED_LOOT_SEEDS = BUILDER
        .comment("Whether generated structure container loot seeds should include current system time.")
        .define("timeBasedGeneratedLootSeeds", true);

    static final ModConfigSpec SPEC = BUILDER.build();

    public static boolean enabled;
    public static int cleanupIntervalDays;
    public static int keptCenterRadiusChunks;
    public static int playerProtectionRadiusChunks;
    public static int chunksPerTick;
    public static boolean timeBasedGeneratedLootSeeds;

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        enabled = ENABLED.get();
        cleanupIntervalDays = CLEANUP_INTERVAL_DAYS.get();
        keptCenterRadiusChunks = KEPT_CENTER_RADIUS_CHUNKS.get();
        playerProtectionRadiusChunks = PLAYER_PROTECTION_RADIUS_CHUNKS.get();
        chunksPerTick = CHUNKS_PER_TICK.get();
        timeBasedGeneratedLootSeeds = TIME_BASED_GENERATED_LOOT_SEEDS.get();
    }
}
