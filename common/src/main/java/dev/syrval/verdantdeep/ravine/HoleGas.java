package dev.syrval.verdantdeep.ravine;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import net.minecraft.resources.ResourceLocation;

/**
 * Where one hole gives off gas: from its discs, as each theme lists (see {@link DiscTheme.Gas}), at its falls and springs, and
 * over its cavern's floor, and the fog that fills its air (see {@link GasSettings}). It is worked out from the same hashes and
 * shapes as the rest of the hole, not from the world's blocks, so a source may stand where a player has since built; the gas
 * library only feeds the open cells it finds.
 *
 * <p>Each medium has sources of its own. TODO(VOL-082 step 3): once the gas library converts one medium into another, warm
 * discs can give off steam that cools into their mist instead of giving off the mist itself.
 *
 * @param sources the hole's sources, at most {@link #MAX_SOURCES}
 * @param fogs    the hole's fogs, at most {@link #MAX_FOGS}
 * @param flows   where the gas on the hole's discs drifts toward its axis, one for each disc that gives off gas, at most
 *                {@link #MAX_FLOWS}
 * @param dropped how many more sources there were, left out so that what a hole places stays bounded
 */
public record HoleGas(List<GasSource> sources, List<GasFog> fogs, List<GasFlow> flows, int dropped) {
    public static final HoleGas NONE = new HoleGas(List.of(), List.of(), List.of(), 0);
    /** The most sources one hole places. */
    public static final int MAX_SOURCES = 4096;
    /** The most fogs one hole places. */
    public static final int MAX_FOGS = 257;
    /** The most flow regions one hole places. */
    public static final int MAX_FLOWS = 256;
    // A flow region covers from this far under a disc's platform to this far above it.
    private static final double FLOW_BELOW = 1;
    private static final double FLOW_ABOVE = 8;
    private static final int GAS_HASH_BASE = 2_000_000;
    // Hash indices kept apart for this many gas entries per theme.
    private static final int MAX_GASES = 64;
    private static final int DISC_FOG_HASH_BASE = 2_200_000;
    private static final int CAVERN_HASH_BASE = 2_100_000;
    private static final int CHUNK_SIZE = 16;
    // Sources on the cavern's floor keep this share of its radius in, clear of the wall's bumps.
    private static final double CAVERN_INSIDE = 0.95;

    public HoleGas {
        sources = List.copyOf(sources);
        fogs = List.copyOf(fogs);
        flows = List.copyOf(flows);
        if (flows.size() > MAX_FLOWS) {
            throw new IllegalArgumentException("A hole has at most " + MAX_FLOWS + " flows, got " + flows.size());
        }
        if (fogs.size() > MAX_FOGS) {
            throw new IllegalArgumentException("A hole has at most " + MAX_FOGS + " fogs, got " + fogs.size());
        }
    }

    static HoleGas of(RavineSettings settings, RavineBounds bounds, RavineCell cell, CellDiscs discs) {
        List<GasSource> found = new ArrayList<>();
        List<GasFog> fogs = new ArrayList<>();
        // The hole's own first: there are few of them, and a cap that bites takes the last of the themes' many.
        float surfaceRate = settings.dressing().gas().map(GasSettings::surfaceRate).orElse(1F);
        settings.dressing().gas().ifPresent(gas -> {
            gas.fog().ifPresent(fog -> fogs.add(fromFog(settings, fog, bounds, cell)));
            gas.discFog().ifPresent(fog -> fromDiscFog(fog, bounds, cell, discs, fogs));
            gas.falls().ifPresent(falls -> fromFalls(falls, discs, surfaceRate, found));
            gas.springs().ifPresent(springs -> fromSprings(springs, discs, surfaceRate, found));
            fromCavern(settings, gas.cavern(), bounds, cell, found);
        });
        fromThemes(settings, cell, discs, surfaceRate, found);
        List<GasFlow> flows = flowsOf(discs, settings.dressing().gas().map(GasSettings::pullSpeed).orElse(0.4F));
        int dropped = Math.max(0, found.size() - MAX_SOURCES);
        return new HoleGas(found.subList(0, found.size() - dropped), fogs, flows, dropped);
    }

    // One drawn field per disc: no extra simulated gas cells or per-tick placement. Independent hashes keep the
    // thickness and dense selection stable across reloads without changing terrain or theme draws.
    private static void fromDiscFog(GasSettings.DiscFog fog, RavineBounds bounds, RavineCell cell, CellDiscs discs, List<GasFog> found) {
        for (int i = 0; i < discs.layout().discs().size() && found.size() < MAX_FOGS; i++) {
            Disc disc = discs.layout().discs().get(i);
            if (discs.layout().isSurface(disc)) {
                continue;
            }
            double depth = Math.clamp((bounds.topY() - disc.floor()) / (double) Math.max(1, bounds.topY() - bounds.floorY()), 0, 1);
            double chance = fog.shallowChance() + depth * (fog.deepChance() - fog.shallowChance());
            boolean dense = RavineCells.unit(cell.hash(), DISC_FOG_HASH_BASE + 2 * i) < chance;
            double draw = RavineCells.unit(cell.hash(), DISC_FOG_HASH_BASE + 2 * i + 1);
            float density = dense ? fog.denseDensity() * (float) (0.7 + 0.3 * draw)
                    : (float) (fog.minDensity() + draw * (fog.maxDensity() - fog.minDensity()));
            float radius = (float) disc.radius();
            found.add(new GasFog(dense ? fog.denseMedium() : fog.medium(), disc.x(), disc.floor() - 1, disc.z(),
                    radius, radius, fog.height(), density, 0, Math.min(3, Math.min(radius / 3, fog.height() / 3))));
        }
    }

    // One region for each disc whose theme gives off gas, moving those gases, standing from under the platform's top (its middle
    // is at `floor`) to above it.
    private static List<GasFlow> flowsOf(CellDiscs discs, float speed) {
        List<Disc> all = discs.layout().discs();
        List<GasFlow> flows = new ArrayList<>();
        for (int i = 0; i < all.size() && flows.size() < MAX_FLOWS; i++) {
            Disc disc = all.get(i);
            var media = new LinkedHashSet<ResourceLocation>();
            discs.themes().get(i).ifPresent(theme -> theme.gas().forEach(gas -> media.add(gas.medium())));
            if (!media.isEmpty()) {
                flows.add(new GasFlow(List.copyOf(media), disc.x(), disc.floor() - FLOW_BELOW, disc.z(), (float) disc.radius(),
                        (float) (FLOW_BELOW + FLOW_ABOVE), speed));
            }
        }
        return flows;
    }

    // Chunk by chunk, as growth is, because what keeps lava clear of what burns is worked out for a chunk at a time.
    private static void fromThemes(RavineSettings settings, RavineCell cell, CellDiscs discs, float surfaceRate, List<GasSource> found) {
        List<Disc> all = discs.layout().discs();
        int minChunkX = Integer.MAX_VALUE;
        int maxChunkX = Integer.MIN_VALUE;
        int minChunkZ = Integer.MAX_VALUE;
        int maxChunkZ = Integer.MIN_VALUE;
        for (int i = 0; i < all.size(); i++) {
            if (discs.themes().get(i).map(theme -> theme.gas().isEmpty()).orElse(true)) {
                continue;
            }
            Disc disc = all.get(i);
            double reach = disc.radius() + 1;
            minChunkX = Math.min(minChunkX, Math.floorDiv((int) Math.floor(disc.x() - reach), CHUNK_SIZE));
            maxChunkX = Math.max(maxChunkX, Math.floorDiv((int) Math.floor(disc.x() + reach), CHUNK_SIZE));
            minChunkZ = Math.min(minChunkZ, Math.floorDiv((int) Math.floor(disc.z() - reach), CHUNK_SIZE));
            maxChunkZ = Math.max(maxChunkZ, Math.floorDiv((int) Math.floor(disc.z() + reach), CHUNK_SIZE));
        }
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                fromThemesIn(settings, cell, discs, surfaceRate, chunkX * CHUNK_SIZE, chunkZ * CHUNK_SIZE, found);
            }
        }
    }

    private static void fromThemesIn(
            RavineSettings settings, RavineCell cell, CellDiscs discs, float surfaceRate, int minX, int minZ, List<GasSource> found) {
        List<Disc> all = discs.layout().discs();
        DiscShape shape = settings.discs();
        var lava = new DiscLava(discs, minX, minZ);
        for (int i = 0; i < all.size(); i++) {
            Optional<DiscTheme> theme = discs.themes().get(i);
            if (theme.isEmpty() || theme.get().gas().isEmpty()) {
                continue;
            }
            Disc disc = all.get(i);
            List<DiscTheme.Gas> gas = theme.get().gas();
            double wildness = theme.get().wildness().factorFor(cell.hash(), i);
            DiscGround ground = discs.grounds().get(i);
            float share = rateOn(discs, i, surfaceRate);
            int index = i;
            for (int g = 0; g < Math.min(gas.size(), MAX_GASES); g++) {
                DiscTheme.Gas each = gas.get(g);
                DiscScatter.forEachPick(cell.hash(), GAS_HASH_BASE + i * MAX_GASES + g, disc, each.on(), each.every(), wildness,
                        (x, z) -> each.weightAt(cell.hash(), index, disc, x, z), minX, minZ, (x, z, fromAxis) -> {
                            OptionalDouble y = anchorOf(each, theme.get(), disc, ground, shape, lava, cell.hash(), index, x, z, fromAxis);
                            if (y.isPresent()) {
                                found.add(new GasSource(each.medium(), each.shape(), x + 0.5, y.getAsDouble(), z + 0.5, each.rate() * share, each.rise()));
                            }
                        });
            }
        }
    }

    // The height of a source's anchor in a picked column, or empty where its surface is not there. Over water the gas rests on
    // the water, where a growth starts on the bed; under the disc it hangs from the lowest rock, whose underside is the top of
    // the open block a growth starts in; everywhere else it rests where a growth would start.
    private static OptionalDouble anchorOf(
            DiscTheme.Gas gas, DiscTheme theme, Disc disc, DiscGround ground, DiscShape shape, DiscLava lava, long hash, int index, int x, int z,
            double fromAxis) {
        return switch (gas.on()) {
            case WATER -> {
                Optional<DiscTerraces.Water> pool = ground.terraces().flatMap(plan -> plan.waterAt(x, z));
                if (pool.isPresent()) {
                    yield OptionalDouble.of(gas.shape().standingOn(pool.get().level() + 1));
                }
                int depth = theme.water().filter(water -> water.fluid() == DiscWater.Fluid.WATER).map(water -> water.depthAt(disc, hash, index, x, z)).orElse(0);
                yield depth > 0 ? OptionalDouble.of(gas.shape().standingOn(disc.topBlockAt(fromAxis) + 1)) : OptionalDouble.empty();
            }
            case UNDERSIDE -> {
                OptionalInt open = disc.hangBlockAt(shape, x, z);
                yield open.isPresent() ? OptionalDouble.of(gas.shape().hangingFrom(open.getAsInt() + 1)) : OptionalDouble.empty();
            }
            default -> {
                OptionalInt open = DiscGrowth.placeOn(gas.on(), theme, disc, ground, shape, lava, hash, index, x, z, fromAxis);
                yield open.isPresent() ? OptionalDouble.of(gas.shape().standingOn(open.getAsInt())) : OptionalDouble.empty();
            }
        };
    }

    // What share of its rate a source on a disc gives: less on a surface disc.
    private static float rateOn(CellDiscs discs, int disc, float surfaceRate) {
        return discs.layout().isSurface(discs.layout().discs().get(disc)) ? surfaceRate : 1;
    }

    // Of a cone, from round its base and the cavern at the floor to round its mouth at the top, or to its above_entrance over
    // the bowl's join; of a ravine, the cavern. It reaches an edge past the walls, and as far again as their unevenness moves
    // them, and an edge below the floor, so that it is at its full thickness wherever they are.
    private static GasFog fromFog(RavineSettings settings, GasSettings.Fog fog, RavineBounds bounds, RavineCell cell) {
        float edge = fog.edge();
        double base = bounds.floorY() - edge;
        double reach = edge + settings.wallNoise().maxDisplacement();
        if (settings.cone().isEmpty()) {
            float radius = (float) (settings.cavernRadius() + reach);
            return new GasFog(fog.medium(), cell.centreX(), base, cell.centreZ(), radius, radius, settings.cavernHeight() + 2 * edge,
                    fog.bottomDensity(), fog.topDensity(), edge);
        }
        ConeSettings cone = settings.cone().get();
        double ceiling = fog.aboveEntrance()
                .map(above -> Math.min(bounds.topY(), ConeShape.entranceFloor(cone, bounds, cell) + above))
                .orElse((double) bounds.topY());
        double height = ceiling - base;
        double top = ConeShape.radiusAt(settings, cone, bounds, ceiling) + reach;
        // The narrowest that, with that top, holds the wall that far in at every height it fills.
        double bottom = top;
        for (int y = bounds.floorY(); y < ceiling; y++) {
            double up = (y - base) / height;
            bottom = Math.max(bottom, (wallAt(settings, cone, bounds, y) + reach - top * up) / (1 - up));
        }
        return new GasFog(fog.medium(), cell.centreX(), base, cell.centreZ(), (float) bottom, (float) top, (float) height,
                fog.bottomDensity(), fog.topDensity(), edge);
    }

    // How far the hole's wall is from its axis before its unevenness: the cone's, or the cavern's dome where that is wider.
    private static double wallAt(RavineSettings settings, ConeSettings cone, RavineBounds bounds, int y) {
        double t = (y - bounds.floorY()) / (double) settings.cavernHeight();
        double cavern = t >= 0 && t < 1 ? settings.cavernRadius() * Math.sqrt(1 - t * t) : 0;
        return Math.max(ConeShape.radiusAt(settings, cone, bounds, y), cavern);
    }

    // A fall's water comes down through `drop` open blocks under its level onto the water it lands in, whose top is the floor of
    // the lowest of them.
    private static void fromFalls(GasSettings.Falls falls, CellDiscs discs, float surfaceRate, List<GasSource> found) {
        for (DiscFalls.Fall fall : discs.falls().falls()) {
            double x = fall.x() + 0.5;
            double z = fall.z() + 0.5;
            int landing = fall.level() - fall.drop();
            float share = rateOn(discs, fall.from(), surfaceRate);
            falls.spray().ifPresent(spray -> found.add(
                    new GasSource(spray.medium(), spray.shape(), x, spray.shape().standingOn(landing), z, spray.rate() * share, spray.rise())));
            falls.curtain().ifPresent(curtain -> found.add(new GasSource(curtain.medium(), new GasShape.Cylinder(fall.width() / 2F + curtain.margin(), fall.drop()),
                    x, landing, z, curtain.rate() * share, curtain.rise())));
        }
    }

    // A spring's level is the top of the pool its water comes down in.
    private static void fromSprings(GasSettings.Source springs, CellDiscs discs, float surfaceRate, List<GasSource> found) {
        for (DiscFalls.Spring spring : discs.falls().springs()) {
            found.add(new GasSource(springs.medium(), springs.shape(), spring.x() + 0.5, springs.shape().standingOn(spring.level() + 1), spring.z() + 0.5,
                    springs.rate() * rateOn(discs, spring.disc(), surfaceRate), springs.rise()));
        }
    }

    // The cavern's floor is level at the hole's floor, round the cell's centre.
    private static void fromCavern(RavineSettings settings, List<GasSettings.Spread> cavern, RavineBounds bounds, RavineCell cell, List<GasSource> found) {
        double radius = settings.cavernRadius() * CAVERN_INSIDE;
        int fromX = (int) Math.floor(cell.centreX() - radius);
        int toX = (int) Math.ceil(cell.centreX() + radius);
        int fromZ = (int) Math.floor(cell.centreZ() - radius);
        int toZ = (int) Math.ceil(cell.centreZ() + radius);
        for (int e = 0; e < cavern.size(); e++) {
            GasSettings.Spread spread = cavern.get(e);
            double y = spread.shape().standingOn(bounds.floorY());
            for (int x = fromX; x <= toX; x++) {
                for (int z = fromZ; z <= toZ; z++) {
                    if (Math.hypot(x + 0.5 - cell.centreX(), z + 0.5 - cell.centreZ()) <= radius
                            && RavineCells.unitAt(cell.hash(), CAVERN_HASH_BASE + e, x, z) * spread.every() < 1) {
                        found.add(new GasSource(spread.medium(), spread.shape(), x + 0.5, y, z + 0.5, spread.rate(), spread.rise()));
                    }
                }
            }
        }
    }
}
