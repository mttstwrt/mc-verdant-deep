package dev.syrval.verdantdeep.ravine;

import java.util.List;

/**
 * The discs of one hole or ravine. A geometry implements this, and {@link Discs} does the rest, so discs behave the same in every
 * geometry. Each disc is a pure function of the hole's hash and its grid position, so a layout is built once for a cell and
 * then only read, which makes it safe to share between worker threads.
 */
interface DiscLayout {

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
