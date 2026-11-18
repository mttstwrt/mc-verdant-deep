package dev.syrval.verdantdeep.ravine;

import java.util.List;

/**
 * The discs of one hole or ravine. A geometry implements this, and {@link Discs} does the rest, so discs behave the same in every
 * geometry. Each disc is a pure function of the hole's hash and its grid position, so a layout is built once for a cell and
 * then only read, which makes it safe to share between worker threads.
 */
interface DiscLayout {
    int SHADE_HASH_BASE = 2_400_000;
    // Blocks across the patches by which the shade's ground reaches into the sun and the sun's into the shade.
    double SHADE_EDGE = 5;

    /** Every disc, in a fixed order, each with the support it has among the others. */
    List<Disc> discs();

    /** An open bowl shelf: surface themes and water apply, and it does not excavate an underground dome. */
    default boolean isSurface(Disc disc) {
        return false;
    }

    /**
     * How much of the sun reaches a column of a disc, from 0 in shade to 1 (see {@link SunSettings}). A layout that knows of no
     * sun has it everywhere: growth marked {@code sun} grows as if unmarked, and growth marked {@code shade} not at all.
     */
    default double sunAt(Disc disc, double x, double z) {
        return 1;
    }

    /**
     * Whether a column of the {@code index}-th disc lies in the shade's ground rather than the sun's. Where the sun is partly
     * there the two meet in patches, the shade's taking as much of the ground as the sun lacks, so the floor turns from the
     * one to the other over the same width as the growth does. A disc's shade palette and its sun-only inherited features
     * both follow this, so grass stops where the moss starts.
     */
    default boolean shadedAt(long hash, int index, Disc disc, int x, int z) {
        double sun = sunAt(disc, x, z);
        if (sun >= 1) {
            return false;
        }
        return sun <= 0 || RavineCells.shareBelow(RavineCells.smoothOver(hash, SHADE_HASH_BASE + index, x, z, SHADE_EDGE)) >= sun;
    }

    /** The theme's volume; surface layouts can exclude the buried backs of wall-attached shelves. */
    default boolean biomeContains(Disc disc, DiscShape shape, double x, double y, double z, double margin) {
        return disc.biomeContains(shape, x, y, z, margin);
    }

    /**
     * The discs that can matter to the carve at a column, in the same order: every disc within the carve's falloff of it, and
     * maybe more. A layout of few discs gives all of them.
     */
    default List<Disc> near(double x, double z) {
        return discs();
    }
}
