package com.helloluckyhuang.endrefresh.mixin;

import com.helloluckyhuang.endrefresh.LootSeedRandomizer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(StructurePiece.class)
public abstract class StructurePieceMixin {
    @Redirect(
        method = "createChest(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/world/level/levelgen/structure/BoundingBox;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/world/level/block/state/BlockState;)Z",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/util/RandomSource;nextLong()J")
    )
    private long endrefresh$useTimeBasedChestLootSeed(RandomSource random) {
        return LootSeedRandomizer.mixWithCurrentTime(random.nextLong(), random);
    }

    @Redirect(
        method = "createDispenser",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/util/RandomSource;nextLong()J")
    )
    private long endrefresh$useTimeBasedDispenserLootSeed(RandomSource random) {
        return LootSeedRandomizer.mixWithCurrentTime(random.nextLong(), random);
    }
}
