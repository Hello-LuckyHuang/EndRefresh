package com.helloluckyhuang.endrefresh;

public final class LootSeedRandomizer {
    private LootSeedRandomizer() {
    }

    public static long mixWithCurrentTime(long seed, Object salt) {
        if (!Config.timeBasedGeneratedLootSeeds) {
            return seed;
        }

        long time = System.currentTimeMillis() ^ System.nanoTime();
        seed ^= Long.rotateLeft(time, 21);
        seed ^= Long.rotateLeft(System.identityHashCode(salt), 42);
        seed ^= seed >>> 33;
        seed *= 0xff51afd7ed558ccdL;
        seed ^= seed >>> 33;
        seed *= 0xc4ceb9fe1a85ec53L;
        return seed ^ seed >>> 33;
    }
}
