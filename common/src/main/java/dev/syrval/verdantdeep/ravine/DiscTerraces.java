package dev.syrval.verdantdeep.ravine;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.util.InclusiveRange;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;

/**
 * Pools that step down across a disc, as part of its {@link DiscTheme}. Each lies in a terrace of the theme's own ground, a
 * step lower than the one behind it, and its water runs out through a notch in the front of its terrace and falls into the
 * next pool. The chain crosses the middle of the disc, stepping down towards the hole's middle where it can. A terrace is
 * level on top with sides as steep as an outcrop's, and a slope of ground falls away from the stack of them to the platform.
 *
 * <p>Every pool holds its water: each of its columns has ground at least as high as the water on every side, or water of its
 * own level, but where a notch comes out over the next pool, whose water the open side has under it. A disc with no room for
 * a chain of at least two pools laid out like that, with open air over all of its water and the falls between them, has none
 * and is left the theme's plain ground.
 *
 * @param pools  how many pools, at most; fewer where the disc has no room for them
 * @param radius blocks from the middle of a pool to its edge
 * @param step   blocks from one pool's water down to the next one's
 * @param depth  how deep the middle of a pool is; its edge and the notch are 1 deep
 * @param bank   blocks of level ground round a pool on its terrace, out to the terrace's edge
 * @param rim    what the edge of each pool and notch and the face of each terrace are made of, in place of the theme's
 *               ground: every block of ground beside the water down to its bed, and the blocks of a terrace's side that
 *               stand over the ground beside it. Without one they are the theme's ground
 */
public record DiscTerraces(
        InclusiveRange<Integer> pools, InclusiveRange<Integer> radius, InclusiveRange<Integer> step, int depth, int bank, Optional<BlockStateProvider> rim) {
    static final Codec<DiscTerraces> CODEC = RecordCodecBuilder.create(i -> i.group(
            InclusiveRange.codec(Codec.intRange(2, 8)).fieldOf("pools").forGetter(DiscTerraces::pools),
            InclusiveRange.codec(Codec.intRange(2, 12)).fieldOf("radius").forGetter(DiscTerraces::radius),
            InclusiveRange.codec(Codec.intRange(1, 4)).optionalFieldOf("step", new InclusiveRange<>(1, 2)).forGetter(DiscTerraces::step),
            Codec.intRange(1, DiscWater.MAX_DEPTH).optionalFieldOf("depth", DiscWater.MAX_DEPTH).forGetter(DiscTerraces::depth),
            Codec.intRange(1, 8).optionalFieldOf("bank", 2).forGetter(DiscTerraces::bank),
            BlockStateProvider.CODEC.optionalFieldOf("rim").forGetter(DiscTerraces::rim)
    ).apply(i, DiscTerraces::new));

    private static final int HASH_BASE = 2_300_000;
    // Hash indices kept apart for each disc: the count, then three for each pool.
    private static final int PER_DISC = 64;
    private static final int DRAWS_A_POOL = 3;
    // Blocks of slope for each block it falls, round the stack of terraces.
    private static final double RUN = 1.5;
    // The chain is first laid straight for the hole's middle, then turned this far to either side, and then further.
    private static final double[] TURNS = {0, 0.5, -0.5, 1.0, -1.0, Math.PI / 2, -Math.PI / 2};
    // Then moved this share of the room towards the back of the disc, or the front.
    private static final double[] SHIFTS = {0, 0.35, -0.35};
    // Air is open this far from rock or the hole's wall.
    private static final double CLEAR_AIR = 0.5;
    // How far one pool's chain may bend from the line of the one before, at most, either way.
    private static final double BEND = 0.35;
    // A pool overlaps the terrace behind it by this much, so that the notch's open side is over its water.
    private static final int OVERLAP = 1;
    // Nothing a disc grows starts within this many blocks of a notch or of the water falling from it.
    private static final double CLEAR = 4;
    private static final int BESIDE_IN_HEIGHT = 6;
    private static final int[][] BESIDE = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] ROUND = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    private static final int CHUNK_BITS = 4;

    /** Whether a block is open air, clear of the hole's wall, the roof of a dome and every disc's rock. */
    @FunctionalInterface
    interface Open {
        boolean at(int x, int y, int z);
    }

    /** One terrace: the middle of its pool, the pool's radius, how far from the middle its level top reaches, and its height. */
    record Terrace(int x, int z, int pool, int reach, int level) {
        double distanceTo(int px, int pz) {
            return Math.hypot(px - x, pz - z);
        }
    }

    /** A column of a pool or its notch: the height of its top block of water, how deep it is, and whether it is a notch's last. */
    record Water(int level, int depth, boolean lip) {}

    /** The falling water out of one notch: the open column beside its last block, from the notch's level down to the next pool. */
    record Spill(int x, int z, int top, int bottom) {}

    /** The terraces of each disc of a cell, in the layout's order: empty for a disc whose theme has none, or that has no room. */
    static List<Optional<Plan>> of(RavineSettings settings, RavineBounds bounds, RavineCell cell, DiscLayout layout, List<Optional<DiscTheme>> themes) {
        List<Disc> discs = layout.discs();
        // The most each disc's mounds and outcrops may stand, before ruins and falls level any of them.
        var grounds = new ArrayList<DiscGround>(discs.size());
        for (int i = 0; i < discs.size(); i++) {
            Optional<DiscTheme> theme = themes.get(i);
            grounds.add(new DiscGround(discs.get(i), cell.hash(), i, theme.map(DiscTheme::ground).orElse(DiscRelief.FLAT), theme.flatMap(DiscTheme::water), List.of()));
        }
        var plans = new ArrayList<Optional<Plan>>(themes.size());
        for (int i = 0; i < themes.size(); i++) {
            Disc disc = discs.get(i);
            double inward = Math.atan2(cell.centreZ() - disc.z(), cell.centreX() - disc.x());
            int index = i;
            // Clear of the wall, a dome's roof and every disc's rock; and of other discs' ground, as discs of one layer run
            // into one another.
            Open open = (x, y, z) -> RavineShape.signedDistance(settings, bounds, cell, layout, x, y, z) <= -CLEAR_AIR
                    && RavineShape.rockDistance(settings, bounds, cell, layout, x, y, z) >= CLEAR_AIR
                    && !onAnother(discs, grounds, index, x, y, z);
            plans.add(themes.get(i).flatMap(theme -> theme.ground().terraces())
                    .flatMap(terraces -> terraces.layOut(disc, settings.discs(), cell.hash(), index, inward, open)));
        }
        return List.copyOf(plans);
    }

    private static boolean onAnother(List<Disc> discs, List<DiscGround> grounds, int index, int x, int y, int z) {
        for (int j = 0; j < discs.size(); j++) {
            Disc other = discs.get(j);
            double fromAxis = Math.hypot(x - other.x(), z - other.z());
            if (j != index && fromAxis < other.radius() && y > other.topBlockAt(fromAxis) && y <= grounds.get(j).topBlockAt(x, z)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The terraces of the {@code index}-th disc of a hole, or empty if it has no room for them. {@code inward} is the direction
     * of the hole's middle from the disc, which the chain runs along.
     */
    Optional<Plan> layOut(Disc disc, DiscShape shape, long hash, int index, double inward, Open open) {
        int base = HASH_BASE + index * PER_DISC;
        int count = RootRoutes.countFor(pools, RavineCells.unit(hash, base));
        for (int n = count; n >= 2; n--) {
            for (double turn : TURNS) {
                for (double shift : SHIFTS) {
                    Optional<Plan> plan = tryLayOut(disc, shape, hash, base, inward + turn, shift, n, open);
                    if (plan.isPresent()) {
                        return plan;
                    }
                }
            }
        }
        return Optional.empty();
    }

    private Optional<Plan> tryLayOut(Disc disc, DiscShape shape, long hash, int base, double angle, double shift, int count, Open open) {
        // The terraces' level tops keep within the ground that DiscGround lets rise, inside the level ring at the rim.
        double room = disc.radius() - DiscGround.RIM_FLAT - 1;
        int[] radii = new int[count];
        int[] steps = new int[count];
        for (int k = 0; k < count; k++) {
            radii[k] = RootRoutes.countFor(radius, RavineCells.unit(hash, base + 1 + DRAWS_A_POOL * k));
            steps[k] = RootRoutes.countFor(step, RavineCells.unit(hash, base + 2 + DRAWS_A_POOL * k));
        }
        // Each pool a little across from the line of the one before and on along it; then the whole chain moved so that its
        // middle is the disc's, or some way back or forward of it. The back of a disc is under the lowest of its dome, where
        // the highest terrace may not fit.
        var along = new ArrayList<double[]>(count);
        along.add(new double[] {0, 0});
        double heading = angle;
        for (int k = 1; k < count; k++) {
            heading += BEND * (2 * RavineCells.unit(hash, base + 3 + DRAWS_A_POOL * k) - 1);
            double apart = radii[k - 1] + bank + radii[k] - OVERLAP;
            double[] last = along.getLast();
            along.add(new double[] {last[0] + apart * Math.cos(heading), last[1] + apart * Math.sin(heading)});
        }
        double middleX = (along.getFirst()[0] + along.getLast()[0]) / 2 + shift * room * Math.cos(angle);
        double middleZ = (along.getFirst()[1] + along.getLast()[1]) / 2 + shift * room * Math.sin(angle);
        var placed = new ArrayList<int[]>(count);
        for (int k = 0; k < count; k++) {
            int x = (int) Math.floor(disc.x() + along.get(k)[0] - middleX);
            int z = (int) Math.floor(disc.z() + along.get(k)[1] - middleZ);
            if (Math.hypot(x - disc.x(), z - disc.z()) + radii[k] + bank > room) {
                return Optional.empty();
            }
            placed.add(new int[] {x, z});
        }
        // The lowest first: its level is the highest of the platform under it, and each one behind is a step higher, or more
        // where the platform rises higher under it.
        int[] levels = new int[count];
        for (int k = count - 1; k >= 0; k--) {
            int highest = highestTop(disc, placed.get(k)[0], placed.get(k)[1], radii[k] + bank);
            levels[k] = k == count - 1 ? highest : Math.max(highest, levels[k + 1] + steps[k]);
        }
        var terraces = new ArrayList<Terrace>(count);
        for (int k = 0; k < count; k++) {
            terraces.add(new Terrace(placed.get(k)[0], placed.get(k)[1], radii[k], radii[k] + bank, levels[k]));
        }
        var plan = new Plan(disc, terraces);
        return plan.fill(depth) && plan.holds(shape, open) ? Optional.of(plan) : Optional.empty();
    }

    private static int highestTop(Disc disc, int x, int z, int reach) {
        int highest = Integer.MIN_VALUE;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                if (Math.hypot(dx, dz) <= reach) {
                    highest = Math.max(highest, disc.topBlockAt(Math.hypot(x + dx - disc.x(), z + dz - disc.z())));
                }
            }
        }
        return highest;
    }

    private static long columnOf(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    /** The terraces of one disc as they are laid out: each with its pool, its notch and the water falling from that. Only read once made. */
    static final class Plan {
        private final Disc disc;
        private final List<Terrace> terraces;
        private final Map<Long, Water> water = new LinkedHashMap<>();
        private final Map<Long, Integer> pools = new HashMap<>();
        private final List<Spill> spills = new ArrayList<>();

        private Plan(Disc disc, List<Terrace> terraces) {
            this.disc = disc;
            this.terraces = List.copyOf(terraces);
        }

        List<Terrace> terraces() {
            return terraces;
        }

        List<Spill> spills() {
            return spills;
        }

        /** The height of the level top of the highest terrace over a column, or empty where none is. */
        OptionalInt levelAt(int x, int z) {
            return owner(x, z).map(k -> OptionalInt.of(terraces.get(k).level())).orElse(OptionalInt.empty());
        }

        /** The height the slope round the terraces reaches over a column outside them, in blocks and parts of one. */
        double slopeAt(int x, int z) {
            double highest = Double.NEGATIVE_INFINITY;
            for (Terrace terrace : terraces) {
                highest = Math.max(highest, terrace.level() - (terrace.distanceTo(x, z) - terrace.reach()) / RUN);
            }
            return highest;
        }

        /** A pool's or a notch's water in a column, or empty where there is none. */
        Optional<Water> waterAt(int x, int z) {
            return Optional.ofNullable(water.get(columnOf(x, z)));
        }

        /**
         * Calls {@code sink} with each column of the pools' and notches' water in the chunk whose lowest corner is
         * {@code (minX, minZ)}, as still water whose lips the game is to run, and with the falling water from each notch.
         */
        void forEachWater(int minX, int minZ, DiscFallSink sink) {
            water.forEach((column, held) -> {
                int x = (int) (column >> 32);
                int z = (int) (long) column;
                if (x >> CHUNK_BITS == minX >> CHUNK_BITS && z >> CHUNK_BITS == minZ >> CHUNK_BITS) {
                    sink.accept(x, held.level(), z, held.depth(), 0, held.lip());
                }
            });
            for (Spill spill : spills) {
                if (spill.x() >> CHUNK_BITS == minX >> CHUNK_BITS && spill.z() >> CHUNK_BITS == minZ >> CHUNK_BITS) {
                    sink.flow(spill.x(), spill.z(), spill.top(), spill.bottom());
                }
            }
        }

        /** Whether a block is by a notch or the water falling from it, where nothing grows that would stand in the water's way. */
        boolean isNear(int x, int y, int z) {
            for (Spill spill : spills) {
                if (Math.hypot(x - spill.x(), z - spill.z()) <= CLEAR && y >= spill.bottom() - BESIDE_IN_HEIGHT && y <= spill.top() + BESIDE_IN_HEIGHT) {
                    return true;
                }
            }
            return false;
        }

        /**
         * The lowest block of a column that is the rim (see {@link DiscTerraces}), up to its top; empty where none of it is:
         * a column with water of a pool or notch round it, down to that water's bed, and a column of a terrace with lower
         * ground beside it, down to the block over that ground.
         */
        OptionalInt rimFrom(int x, int z) {
            if (water.containsKey(columnOf(x, z))) {
                return OptionalInt.empty();
            }
            boolean onTerrace = owner(x, z).isPresent();
            int top = groundAt(x, z);
            int from = Integer.MAX_VALUE;
            for (int[] step : ROUND) {
                Water beside = water.get(columnOf(x + step[0], z + step[1]));
                if (beside != null) {
                    from = Math.min(from, beside.level() - beside.depth());
                } else if (onTerrace && Math.abs(step[0]) + Math.abs(step[1]) == 1) {
                    int lower = groundAt(x + step[0], z + step[1]);
                    if (lower < top) {
                        from = Math.min(from, lower + 1);
                    }
                }
            }
            return from == Integer.MAX_VALUE ? OptionalInt.empty() : OptionalInt.of(from);
        }

        /** The round of ground each terrace stands on with {@code margin} more, which nothing else is put on. */
        List<DiscGround.Flat> rounds(double margin) {
            return terraces.stream().map(terrace -> new DiscGround.Flat(terrace.x(), terrace.z(), terrace.reach() + margin)).toList();
        }

        // The terrace a column takes its level from: the highest of those it lies in. The list runs from the highest down.
        private Optional<Integer> owner(int x, int z) {
            for (int k = 0; k < terraces.size(); k++) {
                if (terraces.get(k).distanceTo(x, z) <= terraces.get(k).reach()) {
                    return Optional.of(k);
                }
            }
            return Optional.empty();
        }

        private int groundAt(int x, int z) {
            int rock = disc.topBlockAt(Math.hypot(x - disc.x(), z - disc.z()));
            return Math.max(rock, levelAt(x, z).orElse(rock));
        }

        // The pools, then a notch from each to the next: false where a notch comes to no pool.
        private boolean fill(int deepest) {
            for (int k = 0; k < terraces.size(); k++) {
                Terrace terrace = terraces.get(k);
                for (int x = terrace.x() - terrace.pool(); x <= terrace.x() + terrace.pool(); x++) {
                    for (int z = terrace.z() - terrace.pool(); z <= terrace.z() + terrace.pool(); z++) {
                        double out = terrace.distanceTo(x, z);
                        if (out <= terrace.pool() && owner(x, z).orElse(-1) == k) {
                            put(x, z, k, new Water(terrace.level(), out <= terrace.pool() - 1.5 ? deepest : 1, false));
                        }
                    }
                }
            }
            for (int k = 0; k + 1 < terraces.size(); k++) {
                if (!notch(k)) {
                    return false;
                }
            }
            return true;
        }

        /**
         * A channel a block wide from a pool for the next one's middle, through the bank to the terrace's edge, each column
         * beside the one before so the water runs along it. Its last column has the next pool's water beside it, or there is
         * no notch.
         */
        private boolean notch(int k) {
            Terrace from = terraces.get(k);
            Terrace to = terraces.get(k + 1);
            double apart = from.distanceTo(to.x(), to.z());
            double ux = (to.x() - from.x()) / apart;
            double uz = (to.z() - from.z()) / apart;
            int x = from.x();
            int z = from.z();
            for (double along = 0.5; along <= apart; along += 0.5) {
                int towardX = (int) Math.round(from.x() + ux * along);
                int towardZ = (int) Math.round(from.z() + uz * along);
                while (x != towardX || z != towardZ) {
                    if (Math.abs(towardX - x) >= Math.abs(towardZ - z)) {
                        x += Integer.signum(towardX - x);
                    } else {
                        z += Integer.signum(towardZ - z);
                    }
                    if (owner(x, z).orElse(-1) != k) {
                        return Integer.valueOf(k + 1).equals(pools.get(columnOf(x, z)));
                    }
                    if (!water.containsKey(columnOf(x, z))) {
                        put(x, z, k, new Water(from.level(), 1, false));
                    }
                }
            }
            return false;
        }

        private void put(int x, int z, int pool, Water held) {
            water.put(columnOf(x, z), held);
            pools.put(columnOf(x, z), pool);
        }

        /**
         * Whether all the water stays where it is put: on every side of each column ground at least as high, water of its own
         * level, or water of the pool after or before its own, which it runs into or which runs into it; a block of the
         * platform or the terrace under it; and open air over it and down the fall from each notch. A column with the next
         * pool beside it is a lip, and the water falls into that pool from the open column beside it.
         */
        private boolean holds(DiscShape shape, Open open) {
            var lipped = new ArrayList<Long>();
            var falling = new LinkedHashMap<Long, Spill>();
            for (var entry : water.entrySet()) {
                int x = (int) (entry.getKey() >> 32);
                int z = (int) (long) entry.getKey();
                Water held = entry.getValue();
                int pool = pools.get(entry.getKey());
                double fromAxis = Math.hypot(x - disc.x(), z - disc.z());
                // The water's bed is the platform's own rock or ground on it; the platform has to be thicker than the water is deep.
                if (held.level() - held.depth() < disc.topBlockAt(fromAxis) - (int) Math.floor(shape.floorThickness()) + 1) {
                    return false;
                }
                for (int[] step : BESIDE) {
                    int besideX = x + step[0];
                    int besideZ = z + step[1];
                    long beside = columnOf(besideX, besideZ);
                    Water other = water.get(beside);
                    if (other == null) {
                        if (Math.hypot(besideX - disc.x(), besideZ - disc.z()) >= disc.radius() || groundAt(besideX, besideZ) < held.level()) {
                            return false;
                        }
                    } else if (other.level() != held.level()) {
                        int otherPool = pools.get(beside);
                        if (otherPool == pool + 1) {
                            lipped.add(entry.getKey());
                            falling.putIfAbsent(beside, new Spill(besideX, besideZ, held.level(), other.level() + 1));
                        } else if (otherPool != pool - 1) {
                            return false;
                        }
                    }
                }
                if (!open.at(x, held.level() + 1, z) || !open.at(x, held.level() + 2, z)) {
                    return false;
                }
            }
            for (Spill spill : falling.values()) {
                for (int y = spill.bottom(); y <= spill.top() + 1; y++) {
                    if (!open.at(spill.x(), y, spill.z())) {
                        return false;
                    }
                }
            }
            for (long column : lipped) {
                Water held = water.get(column);
                water.put(column, new Water(held.level(), held.depth(), true));
            }
            spills.addAll(falling.values());
            return true;
        }
    }
}
