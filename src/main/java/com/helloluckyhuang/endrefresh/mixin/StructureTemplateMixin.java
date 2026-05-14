package com.helloluckyhuang.endrefresh.mixin;

import com.helloluckyhuang.endrefresh.LootSeedRandomizer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(StructureTemplate.class)
public abstract class StructureTemplateMixin {
    @Redirect(
        method = "placeInWorld",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/util/RandomSource;nextLong()J")
    )
    private long endrefresh$useTimeBasedLootSeed(RandomSource random) {
        return LootSeedRandomizer.mixWithCurrentTime(random.nextLong(), random);
    }
}
