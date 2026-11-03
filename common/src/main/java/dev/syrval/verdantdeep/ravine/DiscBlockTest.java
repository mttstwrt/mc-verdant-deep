package dev.syrval.verdantdeep.ravine;

/** Asks the level about one block, for a pass that mends what decoration left in a disc (see {@link DiscBlocks#forEachToMend}). */
@FunctionalInterface
public interface DiscBlockTest {
    DiscBlockTest ALL = (x, y, z) -> true;

    boolean test(int x, int y, int z);
}
