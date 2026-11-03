package dev.syrval.verdantdeep.ravine;

import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

/** The biome the level's own biome source puts at a block, before any hole paints its discs' biomes over it. */
@FunctionalInterface
public interface NaturalBiomes {
    /** What a carve reads until its level installs the real biomes: nothing, so that no theme is ruled out by them. */
    NaturalBiomes UNKNOWN = (x, y, z) -> Optional.empty();

    Optional<Holder<Biome>> at(int blockX, int blockY, int blockZ);
}
