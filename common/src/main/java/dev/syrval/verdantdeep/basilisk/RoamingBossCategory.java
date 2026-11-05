package dev.syrval.verdantdeep.basilisk;

import net.minecraft.world.entity.MobCategory;

/**
 * The mob category basilisks spawn in, {@value #NAME}: hostile, at most 3 for a player's surroundings, and gone past 256
 * blocks. As monsters they lost nearly every attempt to the monster cap, which the caves round a hole fill; in a category of
 * their own they have a cap of their own and are the only entry a disc biome lists in it.
 *
 * <p>Each loader adds the constant to MobCategory with these values: NeoForge from {@code META-INF/enumextensions.json}, Fabric
 * from {@code verdant_deep.classtweaker} and its MobCategory mixin. It does not exist when common is compiled, so it is found
 * by name. Biome files name it in {@code spawners} like any other category.
 */
public final class RoamingBossCategory {
    public static final String NAME = "verdant_deep:roaming_boss";

    private RoamingBossCategory() {}

    /** The category; throws if the loader did not add it. Asked once, when the basilisk's entity type is built. */
    public static MobCategory get() {
        for (MobCategory category : MobCategory.values()) {
            if (NAME.equals(category.getName())) {
                return category;
            }
        }
        throw new IllegalStateException("Mob category " + NAME + " was not added by the loader");
    }
}
