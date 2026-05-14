package com.helloluckyhuang.endrefresh.mixin;

import com.helloluckyhuang.endrefresh.LootSeedRandomizer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.RandomizableContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(RandomizableContainer.class)
public interface RandomizableContainerMixin {
    @Redirect(
        method = "setBlockEntityLootTable",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/util/RandomSource;nextLong()J")
    )
    private static long endrefresh$useTimeBasedLootSeed(RandomSource random) {
        return LootSeedRandomizer.mixWithCurrentTime(random.nextLong(), random);
    }
}
