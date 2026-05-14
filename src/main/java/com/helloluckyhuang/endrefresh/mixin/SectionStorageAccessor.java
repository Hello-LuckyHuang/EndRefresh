package com.helloluckyhuang.endrefresh.mixin;

import net.minecraft.world.level.chunk.storage.SectionStorage;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SectionStorage.class)
public interface SectionStorageAccessor {
    @Accessor("simpleRegionStorage")
    SimpleRegionStorage endrefresh$getSimpleRegionStorage();
}
