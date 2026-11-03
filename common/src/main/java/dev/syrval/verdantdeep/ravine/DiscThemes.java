package dev.syrval.verdantdeep.ravine;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

/**
 * Which theme each disc of a cell has: a weighted draw from the cell's hash, with weights that follow the disc's traits, among
 * the themes that may be given where the disc lies.
 */
final class DiscThemes {
    private static final int THEME_HASH_BASE = 200_000;

    private DiscThemes() {}

    /**
     * The theme of each disc of a hole, drawn among those that may be given where it lies: open to the sky at the entrance or
     * under it, and with the natural biome round the mouth on its side (see {@link DiscTheme.Limits}). Where no theme with
     * weight for a disc may be given by that biome, it is drawn as if no theme were kept to biomes, so that the disc keeps a theme.
     */
    static List<Optional<DiscTheme>> assign(
            RavineSettings settings, RavineBounds bounds, RavineCell cell, List<DiscTraits> traits, DiscLayout layout, NaturalBiomes biomes) {
        var assigned = new ArrayList<Optional<DiscTheme>>(traits.size());
        for (int i = 0; i < traits.size(); i++) {
            assigned.add(draw(settings.discThemes(), settings, bounds, cell, traits.get(i), layout, i, biomes));
        }
        return List.copyOf(assigned);
    }

    /**
     * The same themes but for the discs {@code again}, which draw again with the same draw from the themes they could have had
     * that do not need a fall: a disc whose theme needs one but has none is given the theme it would have had without it.
     */
    static List<Optional<DiscTheme>> reassign(
            RavineSettings settings, RavineBounds bounds, RavineCell cell, List<DiscTraits> traits, DiscLayout layout, NaturalBiomes biomes,
            List<Optional<DiscTheme>> themes, Set<Integer> again) {
        List<DiscTheme> dry = settings.discThemes().stream().filter(theme -> !theme.needsFall()).toList();
        var assigned = new ArrayList<>(themes);
        for (int i : again) {
            assigned.set(i, draw(dry, settings, bounds, cell, traits.get(i), layout, i, biomes));
        }
        return List.copyOf(assigned);
    }

    private static Optional<DiscTheme> draw(
            List<DiscTheme> themes, RavineSettings settings, RavineBounds bounds, RavineCell cell, DiscTraits traits, DiscLayout layout, int index,
            NaturalBiomes biomes) {
        Disc disc = layout.discs().get(index);
        boolean surface = layout.isSurface(disc);
        List<DiscTheme> placed = themes.stream().filter(theme -> theme.only().allowWhere(surface)).toList();
        double unit = RavineCells.unit(cell.hash(), THEME_HASH_BASE + index);
        if (placed.stream().anyMatch(theme -> theme.only().near().isPresent())) {
            Optional<Holder<Biome>> around = aroundMouth(settings, bounds, cell, disc, biomes);
            List<DiscTheme> near = placed.stream().filter(theme -> theme.only().allowNear(around)).toList();
            OptionalInt picked = pick(near, traits, unit);
            if (picked.isPresent()) {
                return Optional.of(near.get(picked.getAsInt()));
            }
        }
        OptionalInt picked = pick(placed, traits, unit);
        return picked.isPresent() ? Optional.of(placed.get(picked.getAsInt())) : Optional.empty();
    }

    // The natural biome of the ground round the mouth, straight out from the hole's axis past the disc: on the ring where the
    // mouth's edge is read, at the ground's height there, which is the rim's dip over the edge. A hole without a rim has no
    // mouth of its own, and is asked at its top over the disc.
    private static Optional<Holder<Biome>> aroundMouth(RavineSettings settings, RavineBounds bounds, RavineCell cell, Disc disc, NaturalBiomes biomes) {
        Optional<RimSettings> rim = settings.cone().flatMap(ConeSettings::rim);
        if (rim.isEmpty()) {
            return biomes.at((int) Math.floor(disc.x()), bounds.topY(), (int) Math.floor(disc.z()));
        }
        double dx = disc.x() - cell.centreX();
        double dz = disc.z() - cell.centreZ();
        double out = Math.hypot(dx, dz);
        double reach = settings.cone().get().topRadius();
        double x = cell.centreX() + (out > 0 ? reach * dx / out : reach);
        double z = cell.centreZ() + (out > 0 ? reach * dz / out : 0);
        return biomes.at((int) Math.floor(x), (int) Math.floor(bounds.edgeAt(dx, dz) + rim.get().dip()), (int) Math.floor(z));
    }

    /** Which of the themes a uniform draw in [0, 1) picks for a disc with these traits; empty if none has any weight for it. */
    static OptionalInt pick(List<DiscTheme> themes, DiscTraits traits, double unit) {
        double total = 0;
        for (DiscTheme theme : themes) {
            total += theme.weightFor(traits);
        }
        if (total <= 0) {
            return OptionalInt.empty();
        }
        double left = unit * total;
        int last = -1;
        for (int i = 0; i < themes.size(); i++) {
            double weight = themes.get(i).weightFor(traits);
            if (weight <= 0) {
                continue;
            }
            last = i;
            left -= weight;
            if (left < 0) {
                return OptionalInt.of(i);
            }
        }
        return OptionalInt.of(last);
    }
}
