package dev.syrval.verdantdeep.ravine;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;

/**
 * The ground of one disc: how far it stands over the platform's rock in each column, by its theme's {@link DiscRelief}. The
 * platform keeps its shape, and the ground is written on top of it into open air, as a hole's roots are, so nothing of the
 * terrain, the domes or the stems changes. Where the disc has {@code terraces}, each column of one stands level with its top,
 * whatever else would rise there, and the slope round them is ground as a mound is.
 *
 * <p>The ground lies on the rock wherever something needs it level: near the rim, whose side shows the platform's own layers;
 * at the disc's water and beside it, which the rock holds in; and round each of its {@code flats}, the places where
 * something was stood on the platform itself or let into it: a ruin, a trove, or the water of a fall. Away from each of those it may rise by a block for each block
 * further, so a mound that one of them cuts into ends in a slope and not a wall.
 *
 * @param index the disc's place in its hole's layout, which its patches are drawn by
 */
record DiscGround(
        Disc disc, long hash, int index, DiscRelief relief, Optional<DiscWater> water, List<Flat> flats, Optional<DiscTerraces.Plan> terraces) {
    // Blocks inside the rim where the ground stays on the rock.
    static final double RIM_FLAT = 4;

    /** A round of a disc's top that stays level, the columns nearer than {@code radius} to its middle. */
    record Flat(double x, double z, double radius) {}

    DiscGround {
        flats = List.copyOf(flats);
    }

    /** Ground without terraces. */
    DiscGround(Disc disc, long hash, int index, DiscRelief relief, Optional<DiscWater> water, List<Flat> flats) {
        this(disc, hash, index, relief, water, flats, Optional.empty());
    }

    /** The round of ground each ruin and trove stands on with the margin it keeps, for each of a layout's {@code discs} in their order. */
    static List<List<Flat>> roundsOf(int discs, List<DiscRuinSites.Placed> stood) {
        var rounds = new ArrayList<List<Flat>>(discs);
        for (int i = 0; i < discs; i++) {
            int index = i;
            rounds.add(stood.stream()
                    .filter(placed -> placed.disc() == index)
                    .map(placed -> new Flat(placed.site().x() + 0.5, placed.site().z() + 0.5, placed.radius() + DiscRuinSites.MARGIN))
                    .toList());
        }
        return List.copyOf(rounds);
    }

    /**
     * The ground of each disc of a cell, in the layout's order, level round its {@code ruins} and its falls' water and stepped
     * to its {@code terraces}; a disc with no theme, or a theme with level ground, has none over its rock anywhere.
     */
    static List<DiscGround> of(
            RavineCell cell, List<Disc> discs, List<Optional<DiscTheme>> themes, List<List<Flat>> ruins, DiscFalls falls,
            List<Optional<DiscTerraces.Plan>> terraces) {
        var grounds = new ArrayList<DiscGround>(discs.size());
        for (int i = 0; i < discs.size(); i++) {
            Optional<DiscTheme> theme = themes.get(i);
            var flats = new ArrayList<>(ruins.get(i));
            flats.addAll(falls.clearOf(i));
            grounds.add(new DiscGround(
                    discs.get(i), cell.hash(), i, theme.map(DiscTheme::ground).orElse(DiscRelief.FLAT), theme.flatMap(DiscTheme::water), flats,
                    terraces.get(i)));
        }
        return List.copyOf(grounds);
    }

    /** Whether the disc's ground is its platform everywhere. */
    boolean isFlat() {
        return relief.equals(DiscRelief.FLAT) && terraces.isEmpty();
    }

    /** Blocks of ground over the platform's top block in a column, 0 where the ground is the platform itself. */
    int riseAt(int x, int z) {
        double fromAxis = Math.hypot(x - disc.x(), z - disc.z());
        // A terrace lies inside the ring at the rim and keeps clear of ruins and falls, so it has no say to share.
        OptionalInt terrace = terraces.map(plan -> plan.levelAt(x, z)).orElse(OptionalInt.empty());
        if (terrace.isPresent()) {
            return Math.max(0, terrace.getAsInt() - disc.topBlockAt(fromAxis));
        }
        double fromRim = disc.radius() - RIM_FLAT - fromAxis;
        if (fromRim < 1 || isFlat()) {
            return 0;
        }
        double rise = Math.min(Math.max(Math.max(moundAt(x, z), outcropAt(x, z)), slopeAt(x, z, fromAxis)), fromRim);
        for (Flat flat : flats) {
            rise = Math.min(rise, Math.hypot(x - flat.x(), z - flat.z()) - flat.radius());
        }
        // The water's say is the costliest to ask for, so it is asked last and only of ground that would rise.
        if (rise >= 1 && water.isPresent()) {
            rise = water.get().dryRoomAt(hash, index, x, z, rise);
        }
        return rise < 1 ? 0 : (int) Math.floor(rise);
    }

    /** Whether the ground over a column is an outcrop's material and not the theme's own: where an outcrop stands higher than any mound or slope. */
    boolean isOutcropAt(int x, int z) {
        if (terraces.filter(plan -> plan.levelAt(x, z).isPresent()).isPresent()) {
            return false;
        }
        double outcrop = outcropAt(x, z);
        return outcrop >= 1 && outcrop > moundAt(x, z) && outcrop > slopeAt(x, z, Math.hypot(x - disc.x(), z - disc.z()));
    }

    /**
     * The terraces' rim in a column, if they have one: what it is made of, and the lowest block of it, which runs up to the
     * column's top (see {@link DiscTerraces.Plan#rimFrom}).
     */
    Optional<Rim> rimAt(int x, int z) {
        Optional<BlockStateProvider> block = relief.terraces().flatMap(DiscTerraces::rim);
        if (block.isEmpty() || terraces.isEmpty()) {
            return Optional.empty();
        }
        OptionalInt from = terraces.get().rimFrom(x, z);
        return from.isPresent() ? Optional.of(new Rim(block.get(), from.getAsInt())) : Optional.empty();
    }

    /** A column's rim: its block from {@code from} up. */
    record Rim(BlockStateProvider block, int from) {
        Optional<BlockStateProvider> at(int y) {
            return y >= from ? Optional.of(block) : Optional.empty();
        }
    }

    /** Height of the highest block of ground in a column {@code fromAxis} blocks from the disc's axis; the open block on it is the one above. */
    int topBlockAt(int x, int z) {
        return disc.topBlockAt(Math.hypot(x - disc.x(), z - disc.z())) + riseAt(x, z);
    }

    // Blocks the slope round the terraces stands over the platform there.
    private double slopeAt(int x, int z, double fromAxis) {
        return terraces.map(plan -> plan.slopeAt(x, z) - disc.topBlockAt(fromAxis)).orElse(0.0);
    }

    private double moundAt(int x, int z) {
        return relief.mounds().map(mounds -> mounds.riseAt(hash, index, x, z)).orElse(0.0);
    }

    private double outcropAt(int x, int z) {
        return relief.outcrops().map(outcrops -> outcrops.riseAt(hash, index, x, z)).orElse(0.0);
    }
}
