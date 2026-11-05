package dev.syrval.verdantdeep.fabric.mixin;

import net.minecraft.world.entity.MobCategory;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Gives the constant that {@code extend-enum} in verdant_deep.classtweaker adds to MobCategory its arguments; Fabric has no
 * other way to extend an enum. They match NeoForge's {@code META-INF/enumextensions.json} (see RoamingBossCategory).
 */
@Mixin(MobCategory.class)
public enum MobCategoryMixin {
    VERDANT_DEEP_ROAMING_BOSS("verdant_deep:roaming_boss", 3, false, false, 256);

    MobCategoryMixin(String name, int max, boolean friendly, boolean persistent, int despawnDistance) {}
}
