package dev.syrval.verdantdeep.ravine;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;

/**
 * The discs of one cell, the theme of each, the ruins and the troves that stand on them, the terraces some are stepped into, and the roots that wind among them.
 * All depend only on the settings, the heights the cell's hole lies between, the cell, the ground over it and the pieces the level has for ruins, so they are
 * built once for a cell and then only read, which makes them safe to share between worker threads.
 */
record CellDiscs(
        DiscLayout layout, List<Optional<DiscTheme>> themes, List<RuinSite> ruins, List<RuinSite> troves, List<DiscGround> grounds, RootLayout roots,
        DiscFalls falls, List<Optional<DiscTerraces.Plan>> terraces) {
    // Ruins and falls keep this far from the edge of a terrace, so that the ground they level does not cut into it.
    private static final double FROM_A_TERRACE = 3;

    /** Discs without terraces. */
    CellDiscs(
            DiscLayout layout, List<Optional<DiscTheme>> themes, List<RuinSite> ruins, List<RuinSite> troves, List<DiscGround> grounds, RootLayout roots,
            DiscFalls falls) {
        this(layout, themes, ruins, troves, grounds, roots, falls, themes.stream().map(theme -> Optional.<DiscTerraces.Plan>empty()).toList());
    }

    /** The discs of a hole whose ground is nowhere lower than its top, in a level that has no pieces for ruins. */
    static CellDiscs of(RavineSettings settings, RavineBounds bounds, RavineCell cell) {
        return of(settings, bounds, cell, SurfaceProbe.SOLID, RuinPieces.NONE);
    }

    /** The discs of a hole in a level whose natural biomes are not known, so that no theme is ruled out by them. */
    static CellDiscs of(RavineSettings settings, RavineBounds bounds, RavineCell cell, SurfaceProbe ground, RuinPieces pieces) {
        return of(settings, bounds, cell, ground, pieces, NaturalBiomes.UNKNOWN);
    }

    static CellDiscs of(RavineSettings settings, RavineBounds bounds, RavineCell cell, SurfaceProbe ground, RuinPieces pieces, NaturalBiomes biomes) {
        DiscLayout layout = DiscLayouts.of(settings, bounds, cell, ground);
        List<DiscTraits> traits = DiscTraits.of(settings, cell, layout.discs());
        List<Optional<DiscTheme>> themes = DiscThemes.assign(settings, bounds, cell, traits, layout, biomes);
        RootLayout roots = RootLayout.of(settings, bounds, cell, layout, ground);
        while (true) {
            List<Optional<DiscTerraces.Plan>> terraces = DiscTerraces.of(settings, bounds, cell, layout, themes);
            List<List<DiscGround.Flat>> kept = keptFor(terraces);
            DiscRuinSites.Stood stood = DiscRuinSites.onDiscs(settings, bounds, cell, layout, themes, traits, pieces, roots, kept);
            List<List<DiscGround.Flat>> rounds = DiscGround.roundsOf(layout.discs().size(), stood.all());
            // Falls keep clear of ruins, troves, roots and terraces, and the ground lies level round all but the terraces.
            DiscFalls falls = DiscFalls.of(settings, bounds, cell, layout, themes, traits, joined(rounds, kept), roots, terraces);
            // A disc whose theme needs a fall and found none is given another theme, and everything that depends on the
            // themes is found again. A theme that needs a fall is never given that way, so this ends.
            Set<Integer> dry = withoutTheirFall(themes, falls);
            if (dry.isEmpty()) {
                return new CellDiscs(
                        layout, themes, sitesOf(stood.ruins()), sitesOf(stood.troves()),
                        DiscGround.of(cell, layout.discs(), themes, rounds, falls, terraces), roots, falls, terraces);
            }
            themes = DiscThemes.reassign(settings, bounds, cell, traits, layout, biomes, themes, dry);
        }
    }

    /** The rounds of each disc's ground that its terraces stand on, with the margin that ruins and falls keep from them. */
    List<List<DiscGround.Flat>> kept() {
        return keptFor(terraces);
    }

    private static List<List<DiscGround.Flat>> keptFor(List<Optional<DiscTerraces.Plan>> terraces) {
        return terraces.stream().map(plan -> plan.map(found -> found.rounds(FROM_A_TERRACE)).orElse(List.of())).toList();
    }

    private static List<List<DiscGround.Flat>> joined(List<List<DiscGround.Flat>> first, List<List<DiscGround.Flat>> second) {
        var joined = new ArrayList<List<DiscGround.Flat>>(first.size());
        for (int i = 0; i < first.size(); i++) {
            var both = new ArrayList<>(first.get(i));
            both.addAll(second.get(i));
            joined.add(List.copyOf(both));
        }
        return List.copyOf(joined);
    }

    // The discs whose theme needs a fall that has none starting from it.
    private static Set<Integer> withoutTheirFall(List<Optional<DiscTheme>> themes, DiscFalls falls) {
        var dry = new TreeSet<Integer>();
        for (int i = 0; i < themes.size(); i++) {
            int disc = i;
            if (themes.get(i).filter(DiscTheme::needsFall).isPresent() && falls.falls().stream().noneMatch(fall -> fall.from() == disc)) {
                dry.add(i);
            }
        }
        return dry;
    }

    private static List<RuinSite> sitesOf(List<DiscRuinSites.Placed> stood) {
        return stood.stream().map(DiscRuinSites.Placed::site).toList();
    }

    /** One of the two lists of pieces that stand on the cell's discs. */
    List<RuinSite> sites(DiscSites which) {
        return which == DiscSites.RUINS ? ruins : troves;
    }

    /** Checks the supporting block against each nearby surface platform and its relief, even under another shelf. */
    boolean hasSurfaceGroundAt(DiscShape shape, int x, int y, int z) {
        for (Disc disc : layout.near(x, z)) {
            if (y <= disc.floor() - shape.floorThickness() || !layout.isSurface(disc)) {
                continue;
            }
            double fromAxis = Math.hypot(x - disc.x(), z - disc.z());
            if (fromAxis >= disc.radius() || y <= disc.undersideAt(shape, fromAxis)) {
                continue;
            }
            if (y <= disc.topBlockAt(fromAxis) || y <= grounds.get(layout.discs().indexOf(disc)).topBlockAt(x, z)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The disc a point belongs to, among those whose theme has a biome: the point is in the disc's dome or platform or within
     * {@code margin} of them. Where the spaces of several discs overlap, the highest disc has it, which is the one a rider
     * stands on rather than its host's.
     */
    OptionalInt ownerAt(DiscShape shape, double x, double y, double z, double margin) {
        List<Disc> discs = layout.discs();
        int owner = -1;
        for (int i = 0; i < discs.size(); i++) {
            Disc disc = discs.get(i);
            if ((owner < 0 || disc.floor() > discs.get(owner).floor())
                    && themes.get(i).flatMap(DiscTheme::biome).isPresent() && layout.biomeContains(disc, shape, x, y, z, margin)) {
                owner = i;
            }
        }
        return owner < 0 ? OptionalInt.empty() : OptionalInt.of(owner);
    }
}
