package dev.syrval.verdantdeep.compat;

import dev.syrval.verdantdeep.VerdantDeep;
import dev.syrval.verdantdeep.ravine.DiscSpringSink;
import dev.syrval.verdantdeep.ravine.RavineFootprint;
import dev.syrval.verdantdeep.ravine.WallBowlSettings;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.GrowingPlantBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;

/**
 * Catches what the wall of a hole lets out of the terrain in one chunk (see {@code WallBowlSettings}): each still block of
 * the terrain's water or lava beside the hole's open air is a leak, leaks that lie together are an outflow, and each outflow
 * gets a bowl under it or is plugged. Fluid that would run into a cave of the terrain's within the hole's edge falloff of its
 * open air is plugged as well: such caves often open into the hole, and where their water would come out is not known. This runs once the chunk's decoration is done, vanilla's springs included, and before
 * the chunk's fluids can flow, so a leak never runs before it is dealt with.
 *
 * <p>Only the chunks a hole reaches are looked at, and in them only the sections whose palette holds a fluid, so the wall
 * costs a scan of the few sections that cut through an aquifer, a lava pocket or a spring. A leak is found from the blocks
 * themselves: whether the terrain's noise and its aquifer put fluid where the wall passes is not known before they are made.
 */
final class WallBowls {
    // Where fluid leaves a block: sideways and down, never up.
    private static final Direction[] OUT = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.DOWN};
    // Leaks this close along each axis are one outflow.
    private static final int TOGETHER = 2;
    // More leaks than this make a face too wide for any bowl allowed, and are plugged without working one out.
    private static final int MOST_LEAKS = 400;
    // Columns looked at across a chunk to tell whether a hole reaches it: every fourth block, edges included.
    private static final int SAMPLE_STEP = 5;
    // A fall's own blocks may pass this close to anything it must keep off: it touches nothing on the way down.
    private static final double FALL_CLEARANCE = 1.5;

    // An indirect leak runs first into a cave of the terrain's beside the wall; where it then goes is not known, so it is plugged.
    private record Leak(BlockPos fluid, BlockPos outlet, boolean lava, boolean indirect) {}

    // What became of an outflow, for the debug log.
    private enum Outcome { BOWL, INDIRECT, SMALL, WIDE, NO_CLEAR_FALL, NO_ROOM, NEAR_FIRE }

    private record Bowl(int level, List<BlockPos> pool, List<BlockPos> rim, List<BlockPos> shelf, WallBowlSupport support) {}

    private WallBowls() {}

    static void catchLeaks(WorldGenLevel level, RavineFootprint footprint, ChunkPos chunkPos, WorldgenRandom draws) {
        Optional<WallBowlSettings> found = footprint.wallBowlsAt(chunkPos.getMiddleBlockX(), chunkPos.getMiddleBlockZ());
        if (found.isEmpty() || !reaches(footprint, chunkPos)) {
            return;
        }
        WallBowlSettings settings = found.get();
        List<Leak> leaks = findLeaks(level, footprint, chunkPos, springsNear(footprint, chunkPos));
        var why = new StringBuilder();
        for (List<Leak> outflow : outflows(leaks)) {
            why.setLength(0);
            Outcome outcome = outflow.getFirst().indirect() ? Outcome.INDIRECT
                    : outflow.size() < settings.minLeak() ? Outcome.SMALL
                    : outflow.size() > MOST_LEAKS ? Outcome.WIDE
                    : tryBowl(level, footprint, chunkPos, settings, outflow, draws, why);
            if (outcome != Outcome.BOWL) {
                plug(level, outflow);
            }
            VerdantDeep.LOGGER.debug("Wall outflow of {} {} blocks at {}: {}{}",
                    outflow.size(), outflow.getFirst().lava() ? "lava" : "water", outflow.getFirst().outlet().toShortString(), outcome, why);
        }
    }

    private static boolean reaches(RavineFootprint footprint, ChunkPos chunkPos) {
        for (int dx = 0; dx < 16; dx += SAMPLE_STEP) {
            for (int dz = 0; dz < 16; dz += SAMPLE_STEP) {
                if (footprint.contains(chunkPos.getMinBlockX() + dx, chunkPos.getMinBlockZ() + dz)) {
                    return true;
                }
            }
        }
        return false;
    }

    // The places tried for the hole's own springs round this chunk, whose water is meant to run.
    private static LongSet springsNear(RavineFootprint footprint, ChunkPos chunkPos) {
        LongSet places = new LongOpenHashSet();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                footprint.forEachSpring((chunkPos.x + dx) << 4, (chunkPos.z + dz) << 4, new DiscSpringSink() {
                    @Override
                    public void begin(ResourceKey<ConfiguredFeature<?, ?>> feature) {}

                    @Override
                    public void tryAt(int x, int y, int z) {
                        places.add(BlockPos.asLong(x, y, z));
                    }
                });
            }
        }
        return places;
    }

    private static List<Leak> findLeaks(WorldGenLevel level, RavineFootprint footprint, ChunkPos chunkPos, LongSet springs) {
        ChunkAccess chunk = level.getChunk(chunkPos.x, chunkPos.z);
        List<Leak> leaks = new ArrayList<>();
        var pos = new BlockPos.MutableBlockPos();
        var next = new BlockPos.MutableBlockPos();
        LevelChunkSection[] sections = chunk.getSections();
        for (int index = 0; index < sections.length; index++) {
            LevelChunkSection section = sections[index];
            if (section.hasOnlyAir() || !section.maybeHas(WallBowls::isStillFluid)) {
                continue;
            }
            int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(index));
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (!isStillFluid(state)) {
                            continue;
                        }
                        pos.set(chunkPos.getMinBlockX() + x, baseY + y, chunkPos.getMinBlockZ() + z);
                        if (springs.contains(pos.asLong())) {
                            continue;
                        }
                        boolean terrain = false;
                        for (Direction side : OUT) {
                            next.setWithOffset(pos, side);
                            if (level.isOutsideBuildHeight(next) || !level.getBlockState(next).isAir()) {
                                continue;
                            }
                            boolean open = footprint.isOpen(next.getX(), next.getY(), next.getZ());
                            if (!open && !footprint.isNearOpen(next.getX(), next.getY(), next.getZ())) {
                                continue;
                            }
                            // Asked only once a leak is in sight, as it is the dearer question.
                            if (!terrain && !(terrain = footprint.isTerrainAt(pos.getX(), pos.getY(), pos.getZ()))) {
                                break;
                            }
                            leaks.add(new Leak(pos.immutable(), next.immutable(), state.is(Blocks.LAVA), !open));
                        }
                    }
                }
            }
        }
        return leaks;
    }

    private static boolean isStillFluid(BlockState state) {
        return state.getBlock() instanceof LiquidBlock && state.getFluidState().isSource();
    }

    // Leaks of one fluid whose outlets lie within TOGETHER of each other, joined in chains.
    private static List<List<Leak>> outflows(List<Leak> leaks) {
        int[] parent = new int[leaks.size()];
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < leaks.size(); i++) {
            for (int j = i + 1; j < leaks.size(); j++) {
                Leak a = leaks.get(i);
                Leak b = leaks.get(j);
                if (a.lava() == b.lava() && a.indirect() == b.indirect() && isTogether(a.outlet(), b.outlet())) {
                    parent[root(parent, i)] = root(parent, j);
                }
            }
        }
        Map<Integer, List<Leak>> byRoot = new HashMap<>();
        for (int i = 0; i < leaks.size(); i++) {
            byRoot.computeIfAbsent(root(parent, i), key -> new ArrayList<>()).add(leaks.get(i));
        }
        // In the order the first leak of each was found, so the draws come out the same each time.
        return byRoot.values().stream().sorted((a, b) -> Integer.compare(leaks.indexOf(a.getFirst()), leaks.indexOf(b.getFirst()))).toList();
    }

    private static boolean isTogether(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) <= TOGETHER && Math.abs(a.getY() - b.getY()) <= TOGETHER && Math.abs(a.getZ() - b.getZ()) <= TOGETHER;
    }

    private static int root(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static Outcome tryBowl(
            WorldGenLevel level, RavineFootprint footprint, ChunkPos chunk, WallBowlSettings settings, List<Leak> outflow,
            WorldgenRandom draws, StringBuilder why) {
        boolean lava = outflow.getFirst().lava();
        double clearance = lava ? settings.lavaClearance() : settings.clearance();
        int drop = draws.nextIntBetweenInclusive(settings.drop().minInclusive(), settings.drop().maxInclusive());
        int lowest = outflow.stream().mapToInt(leak -> leak.outlet().getY()).min().orElseThrow();
        // Each column the outflow falls down, from its highest outlet, and the first block under that the fall may not pass
        // through. A column stopped short of the highest pool lands on the wall, where it would spread: its leaks are plugged.
        Map<Long, Integer> tops = new HashMap<>();
        for (Leak leak : outflow) {
            tops.merge(BlockPos.asLong(leak.outlet().getX(), 0, leak.outlet().getZ()), leak.outlet().getY(), Math::max);
        }
        Map<Long, Integer> stops = new HashMap<>();
        var pos = new BlockPos.MutableBlockPos();
        int deepest = lowest - drop - settings.search() - 1;
        for (Map.Entry<Long, Integer> column : tops.entrySet()) {
            int x = BlockPos.getX(column.getKey());
            int z = BlockPos.getZ(column.getKey());
            int y = column.getValue() - 1;
            while (y > deepest && isFallThrough(level, footprint, pos.set(x, y, z))) {
                y--;
            }
            stops.put(column.getKey(), y);
        }
        List<Leak> blocked = new ArrayList<>();
        List<Leak> falling = new ArrayList<>();
        for (Leak leak : outflow) {
            long column = BlockPos.asLong(leak.outlet().getX(), 0, leak.outlet().getZ());
            (stops.get(column) >= lowest - drop ? blocked : falling).add(leak);
        }
        if (falling.size() < settings.minLeak()) {
            return Outcome.NO_CLEAR_FALL;
        }
        tops.keySet().removeIf(column -> stops.get(column) >= lowest - drop);
        int floorOfFalls = tops.keySet().stream().mapToInt(stops::get).max().orElseThrow();
        int minX = tops.keySet().stream().mapToInt(BlockPos::getX).min().orElseThrow();
        int maxX = tops.keySet().stream().mapToInt(BlockPos::getX).max().orElseThrow();
        int minZ = tops.keySet().stream().mapToInt(BlockPos::getZ).min().orElseThrow();
        int maxZ = tops.keySet().stream().mapToInt(BlockPos::getZ).max().orElseThrow();
        if (Math.max(maxX - minX, maxZ - minZ) > 2 * WallBowlSettings.MOST_REACH) {
            return Outcome.WIDE;
        }
        Outcome outcome = Outcome.NO_ROOM;
        why.setLength(0);
        for (int pool = lowest - drop; pool >= lowest - drop - settings.search() && pool > floorOfFalls; pool--) {
            Optional<Bowl> found = fit(level, footprint, chunk, tops.keySet(), settings.margin(), pool, clearance, lava, why);
            if (found.isEmpty()) {
                continue;
            }
            Bowl bowl = found.get();
            if (lava && !isFireproof(level, bowl, tops, settings.fireClearance())) {
                outcome = Outcome.NEAR_FIRE;
                continue;
            }
            build(level, bowl, falling, lava, settings, draws);
            if (!blocked.isEmpty()) {
                plug(level, blocked);
            }
            why.setLength(0);
            why.append(bowl.support().buttress() ? " (buttress)" : " (wall ledge)");
            if (VerdantDeep.LOGGER.isDebugEnabled()) {
                VerdantDeep.LOGGER.debug("Wall catch {} anchors={} support={} pool={} outlets={}", lava ? "lava" : "water",
                        bowl.support().anchors(), bowl.support().blocks(), bowl.pool(), falling.stream().map(Leak::outlet).distinct().toList());
            }
            return Outcome.BOWL;
        }
        return outcome;
    }

    // The pool at one height and what holds it, if it fits there: every block within margin of a fall open and clear, and the
    // blocks round and under it open, which become the lip and the shelf, or already solid enough to hold fluid. The
    // pool follows the falls, so a row of them down a wall gets a long trough along it rather than one wide round basin.
    private static Optional<Bowl> fit(
            WorldGenLevel level, RavineFootprint footprint, ChunkPos chunk, Set<Long> falls, int margin, int pool, double clearance, boolean lava,
            StringBuilder why) {
        var pos = new BlockPos.MutableBlockPos();
        LongSet water = new LongOpenHashSet();
        LongSet seen = new LongOpenHashSet();
        for (long column : falls) {
            int fallX = BlockPos.getX(column);
            int fallZ = BlockPos.getZ(column);
            for (int x = fallX - margin; x <= fallX + margin; x++) {
                for (int z = fallZ - margin; z <= fallZ + margin; z++) {
                    if (Math.hypot(x - fallX, z - fallZ) > margin + 0.5 || !seen.add(BlockPos.asLong(x, pool, z))) {
                        continue;
                    }
                    pos.set(x, pool, z);
                    if (isFree(level, footprint, pos, clearance)) {
                        water.add(pos.asLong());
                    } else if (footprint.isOpen(x, pool, z) && !holdsFluid(level, pos)) {
                        return failed(why, "pool", level, footprint, pos, clearance);
                    }
                }
            }
        }
        for (long column : falls) {
            if (!water.contains(BlockPos.asLong(BlockPos.getX(column), pool, BlockPos.getZ(column)))) {
                return failed(why, "fall", level, footprint, pos.set(BlockPos.getX(column), pool, BlockPos.getZ(column)), clearance);
            }
        }
        // Clipping a wide outflow to the terrain can split its pool. One wall join only supports one basin.
        seen.clear();
        seen.addAll(water);
        if (!isConnected(seen, pool)) {
            if (why.isEmpty()) {
                why.append(" (split pool at y=").append(pool).append(')');
            }
            return Optional.empty();
        }
        List<BlockPos> rim = new ArrayList<>();
        List<BlockPos> shelf = new ArrayList<>();
        LongSet rimColumns = new LongOpenHashSet();
        for (long each : water) {
            int x = BlockPos.getX(each);
            int z = BlockPos.getZ(each);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    long beside = BlockPos.asLong(x + dx, pool, z + dz);
                    if (!water.contains(beside) && rimColumns.add(beside)) {
                        pos.set(x + dx, pool, z + dz);
                        int before = rim.size();
                        if (!holdsOrTakes(level, footprint, pos, clearance / 2, rim)) {
                            return failed(why, "lip", level, footprint, pos, clearance / 2);
                        }
                        // Water has a flush lip across the open face. Retain the raised lip at splash points and for lava.
                        if (rim.size() != before && (lava || besideFall(falls, pos.getX(), pos.getZ()))
                                && !holdsOrTakes(level, footprint, pos.move(Direction.UP), clearance / 2, rim)) {
                            return failed(why, "raised lip", level, footprint, pos, clearance / 2);
                        }
                    }
                }
            }
            pos.set(x, pool - 1, z);
            if (!holdsOrTakes(level, footprint, pos, 0, shelf)) {
                return failed(why, "floor", level, footprint, pos, 0);
            }
        }
        WallBowlSupport support = WallBowlSupport.fit(level, footprint, chunk, pool, water, shelf, clearance);
        if (support == null) {
            if (why.isEmpty()) {
                why.append(" (no wall join at y=").append(pool).append(')');
            }
            return Optional.empty();
        }
        // Extend the solid shelf from the pool floor to the lip. Thicken it only at the join, so the underside slopes
        // back into the wall instead of bulging under the middle of the pool.
        LongSet under = new LongOpenHashSet(water);
        under.addAll(rimColumns);
        support.extendShelf(level, footprint, chunk, pool, under, water, clearance, shelf);
        List<BlockPos> poolBlocks = new ArrayList<>(water.size());
        for (long each : water) {
            poolBlocks.add(BlockPos.of(each));
        }
        return Optional.of(new Bowl(pool, poolBlocks, rim, shelf, support));
    }

    private static boolean besideFall(Set<Long> falls, int x, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (falls.contains(BlockPos.asLong(x + dx, 0, z + dz))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isConnected(LongSet unvisited, int height) {
        long first = unvisited.iterator().nextLong();
        var pending = new LongArrayFIFOQueue();
        pending.enqueue(first);
        unvisited.remove(first);
        while (!pending.isEmpty() && !unvisited.isEmpty()) {
            long cell = pending.dequeueLong();
            int x = BlockPos.getX(cell);
            int z = BlockPos.getZ(cell);
            for (Direction side : Direction.Plane.HORIZONTAL) {
                long next = BlockPos.asLong(x + side.getStepX(), height, z + side.getStepZ());
                if (unvisited.remove(next)) {
                    pending.enqueue(next);
                }
            }
        }
        return unvisited.isEmpty();
    }

    // Notes, for the debug log, the first reason a bowl did not fit in an outflow's search.
    private static Optional<Bowl> failed(
            StringBuilder why, String part, WorldGenLevel level, RavineFootprint footprint, BlockPos pos, double clearance) {
        if (why.isEmpty()) {
            why.append(" (").append(part).append(" at ").append(pos.toShortString()).append(": ")
                    .append(level.getBlockState(pos).getBlock().getDescriptionId()).append(", open ")
                    .append(footprint.isOpen(pos.getX(), pos.getY(), pos.getZ())).append(", clear ")
                    .append(String.format(Locale.ROOT, "%.1f", footprint.clearanceAt(pos.getX(), pos.getY(), pos.getZ(), 8))).append(')');
        }
        return Optional.empty();
    }

    // Stable solid blocks can hold the pool as they are. Open space, clear enough, is taken for the bowl.
    private static boolean holdsOrTakes(WorldGenLevel level, RavineFootprint footprint, BlockPos.MutableBlockPos pos, double clearance, List<BlockPos> taken) {
        if (holdsFluid(level, pos)) {
            return true;
        }
        if (!isFree(level, footprint, pos, clearance)) {
            return false;
        }
        taken.add(pos.immutable());
        return true;
    }

    private static boolean holdsFluid(WorldGenLevel level, BlockPos pos) {
        BlockState there = level.getBlockState(pos);
        return there.isSolidRender(level, pos) && there.getFluidState().isEmpty() && !there.hasBlockEntity()
                && !(there.getBlock() instanceof FallingBlock);
    }

    // Replace small decoration only. In neighbouring chunks an earlier catch may already have put rock in the carved space.
    private static boolean isFree(WorldGenLevel level, RavineFootprint footprint, BlockPos.MutableBlockPos pos, double clearance) {
        if (level.isOutsideBuildHeight(pos)) {
            return false;
        }
        BlockState there = level.getBlockState(pos);
        return there.getFluidState().isEmpty() && !there.hasBlockEntity()
                && (there.isAir() || there.canBeReplaced() || there.is(Blocks.POINTED_DRIPSTONE)
                        || there.getBlock() instanceof GrowingPlantBlock)
                && footprint.isOpen(pos.getX(), pos.getY(), pos.getZ()) && footprint.clearanceAt(pos.getX(), pos.getY(), pos.getZ(), clearance) >= clearance;
    }

    // What a fall runs down through: open air, or a plant its water washes away.
    private static boolean isFallThrough(WorldGenLevel level, RavineFootprint footprint, BlockPos.MutableBlockPos pos) {
        if (level.isOutsideBuildHeight(pos)) {
            return false;
        }
        BlockState there = level.getBlockState(pos);
        return (there.isAir() || there.canBeReplaced() && there.getFluidState().isEmpty())
                && footprint.isOpen(pos.getX(), pos.getY(), pos.getZ()) && footprint.clearanceAt(pos.getX(), pos.getY(), pos.getZ(), FALL_CLEARANCE) >= FALL_CLEARANCE;
    }

    // Nothing lava sets alight within reach of the pool or of the falls into it.
    private static boolean isFireproof(WorldGenLevel level, Bowl bowl, Map<Long, Integer> falls, int reach) {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BlockPos each : bowl.pool()) {
            minX = Math.min(minX, each.getX());
            maxX = Math.max(maxX, each.getX());
            minZ = Math.min(minZ, each.getZ());
            maxZ = Math.max(maxZ, each.getZ());
        }
        int top = falls.values().stream().mapToInt(Integer::intValue).max().orElse(bowl.level());
        var pos = new BlockPos.MutableBlockPos();
        for (int x = minX - reach; x <= maxX + reach; x++) {
            for (int z = minZ - reach; z <= maxZ + reach; z++) {
                for (int y = bowl.level() - reach; y <= top + reach; y++) {
                    if (level.getBlockState(pos.set(x, y, z)).ignitedByLava()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static void build(WorldGenLevel level, Bowl bowl, List<Leak> outflow, boolean lava, WallBowlSettings settings, WorldgenRandom draws) {
        BlockState wallRock = wallRockOf(level, outflow);
        for (List<BlockPos> part : List.of(bowl.shelf(), bowl.rim())) {
            for (BlockPos each : part) {
                level.setBlock(each, lava ? settings.lavaRock().getState(draws, each) : wallRock, Block.UPDATE_CLIENTS);
            }
        }
        // Use the native rock at the join even when the fluid-facing shell is basalt.
        for (BlockPos each : bowl.support().blocks()) {
            level.setBlock(each, wallRock, Block.UPDATE_CLIENTS);
        }
        BlockState fluid = (lava ? Blocks.LAVA : Blocks.WATER).defaultBlockState();
        for (BlockPos each : bowl.pool()) {
            level.setBlock(each, fluid, Block.UPDATE_CLIENTS);
        }
    }

    // The leaks are given the rock round them, which shuts the wall where it cut into the fluid.
    private static void plug(WorldGenLevel level, List<Leak> outflow) {
        BlockState rock = wallRockOf(level, outflow);
        for (Leak leak : outflow) {
            level.setBlock(leak.fluid(), rock, Block.UPDATE_CLIENTS);
        }
    }

    // The block the terrain's solid rock round the leaks is most made of, so that a plug or a bowl looks like the wall.
    private static BlockState wallRockOf(WorldGenLevel level, List<Leak> outflow) {
        Map<BlockState, Integer> counts = new HashMap<>();
        var pos = new BlockPos.MutableBlockPos();
        for (Leak leak : outflow) {
            for (Direction side : Direction.values()) {
                BlockState there = level.getBlockState(pos.setWithOffset(leak.fluid(), side));
                if (there.getFluidState().isEmpty() && there.isSolidRender(level, pos) && !there.hasBlockEntity()
                        && !(there.getBlock() instanceof FallingBlock)) {
                    counts.merge(there, 1, Integer::sum);
                }
            }
        }
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey)
                .orElse(outflow.getFirst().fluid().getY() < 0 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState());
    }
}
