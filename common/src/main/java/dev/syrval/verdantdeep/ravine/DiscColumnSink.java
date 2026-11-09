package dev.syrval.verdantdeep.ravine;

/**
 * Receives each column of a themed disc: the theme's label (as {@code /abyss stats} names it), whether the disc is an open
 * bowl shelf, the share of the sun the column has (see {@link SunSettings}), and the height of the top block of its ground.
 */
@FunctionalInterface
public interface DiscColumnSink {
    void accept(String theme, boolean surface, double sun, int x, int ground, int z);
}
