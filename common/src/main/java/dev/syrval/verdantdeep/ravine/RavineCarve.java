package dev.syrval.verdantdeep.ravine;

import com.mojang.serialization.MapCodec;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.Function;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Density function {@code verdant_deep:ravine_carve}: -1 inside the ravine volume, 1 outside, ramping across
 * {@code edge_falloff} blocks at the walls. Meant to be combined as {@code min(original, carve)}, so terrain outside
 * the ravine keeps its exact sign and therefore its blocks.
 *
 * <p>A cone (see {@link ConeSettings}) also has free-standing structures, which are rock where the original terrain may be
 * air (a cave, or above the surface), so {@code min} cannot make them. {@link #rock()} is a second function of the same type,
 * 1 inside those structures and -1 elsewhere, and the cone is combined as {@code max(min(original, carve), rock)}.
 *
 * <p>Vanilla wiring only hands the world seed to noise holders, so the carve is created unseeded by the codec and
 * re-created with the seed and the level's vertical bounds by the density hook ({@link #bind}); until then it is inert
 * and reads as solid everywhere.
 *
 * <p>The level's bounds are the floor every hole shares and the highest a hole's top may be. A cone whose top follows the
 * ground (see {@link RimSettings}) has a top of its own in each cell, so everything here works with the cell's own bounds
 * ({@link #boundsOf}).
 */
public final class RavineCarve implements DensityFunction.SimpleFunction {
    public static final MapCodec<RavineCarve> MAP_CODEC = RavineSettings.MAP_CODEC.xmap(RavineCarve::of, RavineCarve::settings);
    private static final KeyDispatchDataCodec<RavineCarve> CODEC = KeyDispatchDataCodec.of(MAP_CODEC);

    private static final double CAVERN_BIOME_MARGIN = 4;
    // One biome cell: enough for what stands on a disc's rim or hangs under it to be in the disc's biome.
    private static final double DISC_BIOME_MARGIN = 4;
    private static final int CHUNK_SIZE = 16;

    private final RavineSettings settings;
    private final long seed;
    // Null only for the unbound instance the codec produces.
    private final RavineBounds bounds;
    private final RavineSites sites;
    private final Output output;

    /** Which of a ravine's two functions an instance computes. */
    private enum Output { OPEN, ROCK }

    /** A cell that holds a hole in this level, and the heights that hole lies between. */
    private record Hole(RavineCell cell, RavineBounds bounds) {}

    private RavineCarve(RavineSettings settings, long seed, RavineBounds bounds, RavineSites sites, Output output) {
        this.settings = settings;
        this.seed = seed;
        this.bounds = bounds;
        this.sites = sites;
        this.output = output;
    }

    /** The carve of these settings as the codec makes it: unseeded, and inert until it is bound. */
    public static RavineCarve of(RavineSettings settings) {
        return new RavineCarve(settings, 0, null, new RavineSites(settings, null), Output.OPEN);
    }

    /** The ravine settings behind a datapack reference; fails loudly if the reference is not a ravine carve. */
    public static RavineSettings settingsOf(DensityFunction function) {
        if (function instanceof RavineCarve carve) {
            return carve.settings;
        }
        throw new IllegalStateException("Expected an verdant_deep:ravine_carve density function but got " + function);
    }

    public RavineCarve bind(long seed, RavineBounds bounds) {
        return new RavineCarve(settings, seed, bounds, new RavineSites(settings, bounds), Output.OPEN);
    }

    /**
     * The rock this ravine adds back, which only a cone has: 1 inside its structures, -1 elsewhere. Shares what this carve
     * knows of each cell.
     */
    public RavineCarve rock() {
        return new RavineCarve(settings, seed, bounds, sites, Output.ROCK);
    }

    public RavineSettings settings() {
        return settings;
    }

    public long seed() {
        return seed;
    }

    /** The level's bounds: the floor of every hole, and the highest a hole's top may be. */
    public RavineBounds bounds() {
        return bounds;
    }

    /** Only cells whose sample columns all pass {@code check} hold a ravine. Called once, before any chunk is built. */
    public void restrictToLand(LandCheck check) {
        sites.restrictToLand(check);
    }

    /**
     * A cone whose top follows the ground reads the ground from {@code ground}, and holds no hole where that is too near
     * {@code seaLevel}. Called once, before any chunk is built.
     */
    public void followGround(SurfaceProbe ground, int seaLevel) {
        sites.followGround(ground, seaLevel);
    }

    /** Themes kept to natural biomes read them from {@code biomes}; until then none is ruled out. Called once, before any chunk is built. */
    public void readBiomes(NaturalBiomes biomes) {
        sites.readBiomes(biomes);
    }

    /**
     * The ruins on this ravine's discs are made of {@code pieces}; until then there are none. Called once, before any chunk is
     * built.
     */
    public void furnishRuins(RuinPieces pieces) {
        sites.furnishRuins(pieces);
    }

    /**
     * The heights the cell's hole lies between in this level, or empty if it holds none: the hash may place one, but not over
     * an ocean, nor where the ground is too near sea level for a top that follows it.
     */
    public Optional<RavineBounds> boundsOf(RavineCell cell) {
        return bounds == null ? Optional.empty() : sites.boundsOf(cell);
    }

    /**
     * The cell holding the column, packed as {@link #gasOfCell} takes it. Only arithmetic: whether the cell holds a hole is
     * not asked, since that can mean reading terrain.
     */
    public long cellKeyAt(int x, int z) {
        int size = settings.cellSize();
        return packCell(Math.floorDiv(x, size), Math.floorDiv(z, size));
    }

    /**
     * The gas of the hole in a cell from {@link #cellKeyAt}, or empty if the cell holds none. The first call for a cell works
     * its hole's discs and gas out, which may read terrain and take a while; any thread.
     */
    public Optional<RavineFootprint.HoleGasAt> gasOfCell(long cellKey) {
        return RavineCells.at(seed, settings, cellX(cellKey), cellZ(cellKey))
                .flatMap(cell -> boundsOf(cell).map(own -> new RavineFootprint.HoleGasAt(cell.centreX(), cell.centreZ(), sites.gasOf(cell, own))));
    }

    // Cells are at least 64 blocks, so 24 bits each way hold every cell of a 30 million block world.
    private static long packCell(long cellX, long cellZ) {
        return (cellX & 0xFFFFFFL) << 24 | (cellZ & 0xFFFFFFL);
    }

    // Each half sign-extended back from its 24 bits.
    private static long cellX(long key) {
        return key << 16 >> 40;
    }

    private static long cellZ(long key) {
        return key << 40 >> 40;
    }

    /** Whether the cell holds a ravine in this level. */
    public boolean isActive(RavineCell cell) {
        return boundsOf(cell).isPresent();
    }

    /**
     * What the hole of the cell containing {@code (x, z)} holds, with what each piece holds given by {@code held}; empty if the
     * cell has no hole in this level. Reads the discs the carve has already built for the cell.
     */
    public Optional<ChasmStats> statsAt(int x, int z, Function<RuinSite, ChasmStats.Containers> held) {
        return holeAt(x, z).map(hole -> ChasmStats.of(settings, discsOf(hole), held));
    }

    /**
     * Calls {@code sink} with every column of a themed disc of the hole whose cell contains {@code (x, z)}, a disc's columns
     * together, whatever chunks they lie in. Reads the discs the carve has already built for the cell.
     *
     * @return whether the cell has a hole in this level
     */
    public boolean forEachDiscColumn(int x, int z, DiscColumnSink sink) {
        Optional<Hole> found = holeAt(x, z);
        found.ifPresent(hole -> {
            CellDiscs cellDiscs = discsOf(hole);
            List<Disc> discs = cellDiscs.layout().discs();
            for (int i = 0; i < discs.size(); i++) {
                Optional<DiscTheme> theme = cellDiscs.themes().get(i);
                if (theme.isEmpty()) {
                    continue;
                }
                Disc disc = discs.get(i);
                String label = ChasmStats.labelOf(settings, theme);
                boolean surface = cellDiscs.layout().isSurface(disc);
                DiscGround ground = cellDiscs.grounds().get(i);
                for (int columnX = (int) Math.ceil(disc.x() - disc.radius()); columnX <= Math.floor(disc.x() + disc.radius()); columnX++) {
                    for (int columnZ = (int) Math.ceil(disc.z() - disc.radius()); columnZ <= Math.floor(disc.z() + disc.radius()); columnZ++) {
                        double fromAxis = Math.hypot(columnX - disc.x(), columnZ - disc.z());
                        if (fromAxis <= disc.radius()) {
                            sink.accept(label, surface, cellDiscs.layout().sunAt(disc, columnX, columnZ), columnX,
                                    disc.topBlockAt(fromAxis) + ground.riseAt(columnX, columnZ), columnZ);
                        }
                    }
                }
            }
        });
        return found.isPresent();
    }

    private Optional<Hole> holeAt(int x, int z) {
        return RavineCells.containing(seed, settings, x, z).flatMap(cell -> boundsOf(cell).map(own -> new Hole(cell, own)));
    }

    /** Whether the column at {@code (x, z)} is within reach of an active ravine's cell centre. */
    public boolean isInFootprint(int x, int z) {
        return holeAt(x, z)
                .filter(hole -> hole.cell().distanceToCentre(x, z) <= settings.maxReach())
                .isPresent();
    }

    /**
     * How far down its hole a point is, 0 at the mouth's height to 1 at the floor, or empty if the point is not in the hole's
     * open air, its discs' domes or within {@code margin} blocks of them.
     */
    public OptionalDouble depthShareIn(int x, int y, int z, double margin) {
        Optional<Hole> found = holeAt(x, z);
        if (found.isEmpty()) {
            return OptionalDouble.empty();
        }
        Hole hole = found.get();
        double distance = RavineShape.signedDistance(settings, hole.bounds(), hole.cell(), discsOf(hole).layout(), x, y, z);
        if (distance > margin) {
            return OptionalDouble.empty();
        }
        double floor = hole.bounds().floorY();
        return OptionalDouble.of(Math.clamp((hole.bounds().topY() - y) / (hole.bounds().topY() - floor), 0, 1));
    }

    /**
     * How far down its hole a point is measured from the mouth's edge on the point's own side, 0 at that edge to 1 at the floor,
     * or empty if the point is less than {@code below} blocks under the edge or outside the hole's open air and its discs' domes
     * by more than {@code margin}. On a sloping mouth the uphill side's edge stands above the top, so the bowl under it counts.
     */
    public OptionalDouble rimDepthShareIn(int x, int y, int z, double below, double margin) {
        Optional<Hole> found = holeAt(x, z);
        if (found.isEmpty()) {
            return OptionalDouble.empty();
        }
        Hole hole = found.get();
        double edge = hole.bounds().edgeAt(x - hole.cell().centreX(), z - hole.cell().centreZ());
        if (y > edge - below) {
            return OptionalDouble.empty();
        }
        double distance = RavineShape.signedDistance(settings, hole.bounds(), hole.cell(), discsOf(hole).layout(), x, y, z);
        if (distance > margin) {
            return OptionalDouble.empty();
        }
        double floor = hole.bounds().floorY();
        return OptionalDouble.of(Math.clamp((edge - y) / (edge - floor), 0, 1));
    }

    /** Whether a point is in the cavern's biome volume: the dome plus a margin around its surfaces. */
    public boolean isCavern(int x, int y, int z) {
        return holeAt(x, z)
                .filter(hole -> RavineShape.cavernContains(settings, hole.bounds(), hole.cell(), x, y, z, CAVERN_BIOME_MARGIN))
                .isPresent();
    }

    /**
     * Calls {@code sink} with every block that takes a disc's material in the chunk whose lowest corner is {@code (minX, minZ)},
     * between {@code minY} and {@code maxY} (exclusive). See {@link DiscBlocks}.
     */
    public void forEachDiscBlock(int minX, int minZ, int minY, int maxY, DiscBlockSink sink) {
        if (settings.discThemes().isEmpty()) {
            return;
        }
        // A cell is a whole number of chunks, so the chunk's corner is in the same cell as the rest of it.
        holeAt(minX, minZ).ifPresent(hole -> DiscBlocks.forEach(settings, hole.bounds(), hole.cell(), discsOf(hole), minX, minZ, minY, maxY, sink));
    }

    /** See {@link RavineFootprint#forEachDiscBlockToMend} and {@link DiscBlocks#forEachToMend}. */
    public void forEachDiscBlockToMend(int minX, int minZ, int minY, int maxY, DiscBlockTest loose, DiscBlockSink sink) {
        if (settings.discThemes().isEmpty()) {
            return;
        }
        holeAt(minX, minZ).ifPresent(hole -> DiscBlocks.forEachToMend(settings, hole.bounds(), hole.cell(), discsOf(hole), minX, minZ, minY, maxY, loose, sink));
    }

    /** Calls {@code sink} with every column where a disc holds water in the chunk whose lowest corner is {@code (minX, minZ)}. */
    public void forEachDiscWater(int minX, int minZ, DiscWaterSink sink) {
        if (settings.discThemes().isEmpty()) {
            return;
        }
        holeAt(minX, minZ).ifPresent(hole -> DiscBlocks.forEachWater(hole.cell(), discsOf(hole), minX, minZ, sink));
    }

    /**
     * Calls {@code sink} with every block of ground that stands over a disc's platform in the chunk whose lowest corner is
     * {@code (minX, minZ)}, between {@code minY} and {@code maxY} (exclusive). See {@link DiscGround}.
     */
    public void forEachMoundBlock(int minX, int minZ, int minY, int maxY, DiscBlockSink sink) {
        if (settings.discThemes().isEmpty()) {
            return;
        }
        holeAt(minX, minZ).ifPresent(hole -> DiscBlocks.forEachMound(hole.cell(), discsOf(hole), minX, minZ, minY, maxY, sink));
    }

    /** Calls {@code sink} with every column where a fall's still water lies in the chunk whose lowest corner is {@code (minX, minZ)}. */
    public void forEachFallWater(int minX, int minZ, DiscFallSink sink) {
        if (settings.falls().isEmpty()) {
            return;
        }
        holeAt(minX, minZ).ifPresent(hole -> DiscBlocks.forEachFallWater(discsOf(hole), minX, minZ, sink));
    }

    /**
     * Calls {@code sink} with every spring whose first place is in the chunk whose lowest corner is {@code (minX, minZ)}: the
     * feature that makes it, and the places in the wall to try it at, in order. See {@link DiscFalls}.
     */
    public void forEachSpring(int minX, int minZ, DiscSpringSink sink) {
        Optional<FallSettings.Springs> springs = settings.falls().flatMap(FallSettings::springs);
        if (springs.isEmpty()) {
            return;
        }
        holeAt(minX, minZ).ifPresent(hole -> discsOf(hole).falls().forEachSpring(minX, minZ, spring -> {
            sink.begin(springs.get().feature());
            spring.tries().forEach(place -> sink.tryAt(place.x(), place.y(), place.z()));
        }));
    }

    /** The bowls that catch what the wall of the hole whose cell holds {@code (x, z)} lets out, if the hole has a wall that does. */
    public Optional<WallBowlSettings> wallBowlsAt(int x, int z) {
        return settings.dressing().wallBowls().filter(found -> holeAt(x, z).isPresent());
    }

    /**
     * Whether a block is the terrain's own and not this hole's: outside its open air and away from its discs' rock and its
     * falls, so that fluid there is the terrain's and not a pond or a fall the hole made.
     */
    public boolean isTerrainAt(int x, int y, int z) {
        if (compute(new DensityFunction.SinglePointContext(x, y, z)) <= 0) {
            return false;
        }
        Optional<Hole> found = holeAt(x, z);
        if (found.isEmpty()) {
            return true;
        }
        CellDiscs cellDiscs = discsOf(found.get());
        if (cellDiscs.falls().isNear(x, y, z)) {
            return false;
        }
        for (Disc disc : cellDiscs.layout().near(x, z)) {
            if (disc.rockDistance(settings.discs(), x + 0.5, y + 0.5, z + 0.5) <= 1) {
                return false;
            }
        }
        return true;
    }

    /**
     * How far a block is from what a hole's leaks must keep off, up to {@code cap}: the rock of a disc, the wood of a root, the
     * water of a fall (0 in or by one). {@code cap} where the column has no hole.
     */
    public double clearanceAt(int x, int y, int z, double cap) {
        Optional<Hole> found = holeAt(x, z);
        if (found.isEmpty()) {
            return cap;
        }
        CellDiscs cellDiscs = discsOf(found.get());
        if (cellDiscs.falls().isNear(x, y, z)) {
            return 0;
        }
        double nearest = cellDiscs.roots().surfaceDistance(x + 0.5, y + 0.5, z + 0.5, cap);
        for (Disc disc : cellDiscs.layout().near(x, z)) {
            nearest = Math.min(nearest, disc.rockDistance(settings.discs(), x + 0.5, y + 0.5, z + 0.5));
        }
        return Math.clamp(nearest, 0, cap);
    }

    /**
     * Calls {@code sink} with every block that is root in the chunk whose lowest corner is {@code (minX, minZ)}, between
     * {@code minY} and {@code maxY} (exclusive). See {@link RootBlocks}.
     */
    public void forEachRootBlock(int minX, int minZ, int minY, int maxY, DiscBlockSink sink) {
        if (settings.roots().isEmpty()) {
            return;
        }
        holeAt(minX, minZ).ifPresent(hole -> RootBlocks.forEach(settings, hole.cell(), discsOf(hole), minX, minZ, minY, maxY, sink));
    }

    /**
     * Calls {@code sink} with every place under a root where the roots grow something in the chunk whose lowest corner is
     * {@code (minX, minZ)}, between {@code minY} and {@code maxY} (exclusive). See {@link RootBlocks#forEachGrowth}.
     */
    public void forEachRootGrowth(int minX, int minZ, int minY, int maxY, DiscGrowthSink sink) {
        if (settings.roots().isEmpty() || settings.roots().get().growth().isEmpty()) {
            return;
        }
        holeAt(minX, minZ).ifPresent(hole -> RootBlocks.forEachGrowth(settings, hole.cell(), discsOf(hole), minX, minZ, minY, maxY, sink));
    }

    /**
     * Calls {@code sink} with every place where a disc's theme grows something in the chunk whose lowest corner is
     * {@code (minX, minZ)}. See {@link DiscGrowth}.
     */
    public void forEachGrowth(int minX, int minZ, DiscGrowthSink sink) {
        if (settings.discThemes().isEmpty()) {
            return;
        }
        holeAt(minX, minZ).ifPresent(hole -> DiscGrowth.forEach(settings, hole.cell(), discsOf(hole), minX, minZ, sink));
    }

    /**
     * The ruins or the troves whose middle is in the chunk whose lowest corner is {@code (minX, minZ)}. See
     * {@link DiscRuinSites}. Structure starts are asked for in every chunk of a level, so a chunk out of the hole's reach is
     * answered without building the hole's discs.
     */
    public List<RuinSite> sitesIn(DiscSites which, int minX, int minZ) {
        if (settings.discThemes().stream().allMatch(theme -> which.of(theme).isEmpty())) {
            return List.of();
        }
        return holeAt(minX, minZ)
                .filter(hole -> hole.cell().distanceToCentre(minX + CHUNK_SIZE / 2.0, minZ + CHUNK_SIZE / 2.0) <= settings.maxReach() + CHUNK_SIZE)
                .map(hole -> discsOf(hole).sites(which).stream()
                        .filter(site -> site.x() >= minX && site.x() < minX + CHUNK_SIZE && site.z() >= minZ && site.z() < minZ + CHUNK_SIZE)
                        .toList())
                .orElse(List.of());
    }

    /**
     * The biome of the disc a point belongs to (see {@link CellDiscs#ownerAt}), if there is one: the point is in the disc's
     * dome or platform, or within a margin of them.
     */
    public Optional<ResourceKey<Biome>> discBiomeAt(int x, int y, int z) {
        if (settings.discThemes().isEmpty()) {
            return Optional.empty();
        }
        Optional<Hole> hole = holeAt(x, z).filter(h -> h.cell().distanceToCentre(x, z) <= settings.maxReach() + DISC_BIOME_MARGIN);
        if (hole.isEmpty()) {
            return Optional.empty();
        }
        CellDiscs cellDiscs = discsOf(hole.get());
        OptionalInt owner = cellDiscs.ownerAt(settings.discs(), x, y, z, DISC_BIOME_MARGIN);
        return owner.isPresent() ? cellDiscs.themes().get(owner.getAsInt()).flatMap(DiscTheme::biome) : Optional.empty();
    }

    /**
     * Calls {@code sink} with every disc whose theme inherits a biome's features and whose space reaches into the chunk whose
     * lowest corner is {@code (minX, minZ)}, as a plot to grow those features on.
     */
    public void forEachInheritingDisc(int minX, int minZ, DiscPlotSink sink) {
        if (settings.discThemes().isEmpty()) {
            return;
        }
        holeAt(minX, minZ).ifPresent(hole -> {
            CellDiscs cellDiscs = discsOf(hole);
            List<Disc> all = cellDiscs.layout().discs();
            for (int i = 0; i < all.size(); i++) {
                Disc disc = all.get(i);
                double reach = disc.radius() + DISC_BIOME_MARGIN;
                boolean inChunk = disc.x() + reach >= minX && disc.x() - reach <= minX + 15 && disc.z() + reach >= minZ && disc.z() - reach <= minZ + 15;
                Optional<DiscTheme.Inherits> inherits = cellDiscs.themes().get(i).flatMap(DiscTheme::inherits);
                if (inChunk && inherits.isPresent()) {
                    sink.accept(inherits.get(), new Plot(hole.cell().hash(), cellDiscs, i));
                }
            }
        });
    }

    private final class Plot implements DiscPlot {
        private final long hash;
        private final CellDiscs cellDiscs;
        private final int index;
        private final Disc disc;

        Plot(long hash, CellDiscs cellDiscs, int index) {
            this.hash = hash;
            this.cellDiscs = cellDiscs;
            this.index = index;
            this.disc = cellDiscs.layout().discs().get(index);
        }

        @Override
        public OptionalInt groundAt(int x, int z) {
            double fromAxis = Math.hypot(x - disc.x(), z - disc.z());
            return fromAxis < disc.radius() ? OptionalInt.of(cellDiscs.grounds().get(index).topBlockAt(x, z) + 1) : OptionalInt.empty();
        }

        @Override
        public boolean isSunlit(int x, int z) {
            return !cellDiscs.layout().shadedAt(hash, index, disc, x, z);
        }

        @Override
        public boolean owns(int x, int y, int z) {
            OptionalInt owner = cellDiscs.ownerAt(settings.discs(), x, y, z, DISC_BIOME_MARGIN);
            return owner.isPresent() && owner.getAsInt() == index;
        }
    }

    private CellDiscs discsOf(Hole hole) {
        return sites.discsOf(hole.cell(), hole.bounds());
    }

    @Override
    public double compute(FunctionContext context) {
        int x = context.blockX();
        int y = context.blockY();
        int z = context.blockZ();
        Optional<Hole> found = holeAt(x, z);
        if (found.isEmpty()) {
            return nothing();
        }
        Hole hole = found.get();
        DiscLayout layout = discsOf(hole).layout();
        double distance = output == Output.ROCK
                ? RavineShape.rockDistance(settings, hole.bounds(), hole.cell(), layout, x, y, z)
                : RavineShape.signedDistance(settings, hole.bounds(), hole.cell(), layout, x, y, z);
        return Math.clamp((output == Output.ROCK ? -distance : distance) / settings.edgeFalloff(), minValue(), maxValue());
    }

    // The value where the function has no say: solid for the carve, no added rock for the rock function.
    private double nothing() {
        return output == Output.ROCK ? minValue() : maxValue();
    }

    @Override
    public double minValue() {
        return -1;
    }

    @Override
    public double maxValue() {
        return 1;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return CODEC;
    }
}
