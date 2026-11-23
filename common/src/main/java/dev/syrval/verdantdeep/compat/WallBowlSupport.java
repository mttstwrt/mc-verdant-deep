package dev.syrval.verdantdeep.compat;

import dev.syrval.verdantdeep.ravine.RavineFootprint;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Fits a rock ledge under a basin that already holds its fluid. */
record WallBowlSupport(List<BlockPos> blocks, List<BlockPos> anchors, int reach, Direction towardWall) {
    private static final int MAX_REACH = 6;
    private static final int LEDGE_REACH = 3;

    private record Lane(List<BlockPos> blocks, BlockPos upperAnchor, BlockPos lowerAnchor, int reach) {}
    private record Footing(List<BlockPos> blocks, BlockPos anchor) {}

    record Shoulders(Direction wall, List<BlockPos> blocks) {}

    boolean buttress() {
        return towardWall != Direction.DOWN && reach > LEDGE_REACH;
    }

    static WallBowlSupport fit(WorldGenLevel level, RavineFootprint footprint, ChunkPos chunk, int pool, LongSet water,
            List<BlockPos> dish, double clearance) {
        // A zero configured buffer still leaves planned disc, root and fall blocks to their owners.
        clearance = Math.max(1, clearance);
        WallBowlSupport best = null;
        for (Direction towardWall : Direction.Plane.HORIZONTAL) {
            boolean alongX = towardWall.getAxis() == Direction.Axis.X;
            // Two rows and three adjacent lanes give the catch a rock face to grow out of.
            Lane previous = null;
            Lane middle = null;
            int lastLane = Integer.MIN_VALUE;
            for (var entry : rearEdge(water, towardWall).entrySet()) {
                if (entry.getKey() != lastLane + 1) {
                    previous = null;
                    middle = null;
                }
                Lane next = trace(level, footprint, chunk, entry.getValue(), towardWall, pool, clearance);
                if (joins(previous, middle, alongX) && joins(middle, next, alongX)) {
                    var lanes = List.of(previous, middle, next);
                    int reach = Math.max(previous.reach(), Math.max(middle.reach(), next.reach()));
                    if (best == null || reach < best.reach()) {
                        var blocks = new ArrayList<BlockPos>();
                        var anchors = new ArrayList<BlockPos>(6);
                        for (Lane lane : lanes) {
                            blocks.addAll(lane.blocks());
                            anchors.add(lane.upperAnchor());
                            anchors.add(lane.lowerAnchor());
                            if (reach > LEDGE_REACH) {
                                taper(level, footprint, chunk, lane, towardWall, clearance, blocks);
                            }
                        }
                        blocks.removeAll(dish);
                        best = new WallBowlSupport(blocks, anchors, reach, towardWall);
                    }
                }
                previous = middle;
                middle = next;
                lastLane = entry.getKey();
            }
        }
        if (best == null || best.buttress()) {
            WallBowlSupport ledge = fitBelow(level, footprint, chunk, water, dish, clearance);
            if (ledge != null) {
                return ledge;
            }
        }
        return best;
    }

    // Tie the cup's rear lip into the face across as much of its width as the real rock allows.
    Shoulders shoulders(WorldGenLevel level, RavineFootprint footprint, ChunkPos chunk, LongSet rim, List<BlockPos> dish,
            double clearance) {
        clearance = Math.max(1, clearance);
        LongSet planned = new LongOpenHashSet();
        for (BlockPos block : blocks) {
            planned.add(block.asLong());
        }
        for (BlockPos block : dish) {
            planned.add(block.asLong());
        }
        if (towardWall != Direction.DOWN) {
            return new Shoulders(towardWall, shouldersAlong(level, footprint, chunk, rim, clearance, planned, towardWall));
        }
        Shoulders best = new Shoulders(Direction.DOWN, List.of());
        for (Direction side : Direction.Plane.HORIZONTAL) {
            List<BlockPos> candidate = shouldersAlong(level, footprint, chunk, rim, clearance, planned, side);
            if (candidate.size() > best.blocks().size()) {
                best = new Shoulders(side, candidate);
            }
        }
        return best;
    }

    private static List<BlockPos> shouldersAlong(WorldGenLevel level, RavineFootprint footprint, ChunkPos chunk,
            LongSet rim, double clearance, LongSet planned, Direction side) {
        var shoulders = new ArrayList<BlockPos>();
        // Tracing from the rear edge keeps the added rock outside the basin and its incoming drop.
        for (BlockPos lip : rearEdge(rim, side).values()) {
            if (!footprint.isOpen(lip.getX(), lip.getY(), lip.getZ()) || mayCarryFall(level, chunk, lip)) {
                continue;
            }
            var bridge = new ArrayList<BlockPos>();
            BlockPos wall = anchor(level, footprint, chunk, lip.relative(side), side, 4, clearance, bridge);
            if (wall == null || bridge.stream().anyMatch(pos -> mayCarryFall(level, chunk, pos))) {
                continue;
            }
            shoulders.addAll(bridge);
            for (int step = 0; step <= bridge.size(); step++) {
                BlockPos column = lip.relative(side, step);
                int depth = 3 - (bridge.size() - step);
                for (int down = 1; down <= depth; down++) {
                    BlockPos pos = column.below(down);
                    if (planned.contains(pos.asLong())) {
                        continue;
                    }
                    if (!inRegion(level, chunk, pos) || !canBuild(level, footprint, pos)
                            || footprint.clearanceAt(pos.getX(), pos.getY(), pos.getZ(), clearance) < clearance) {
                        break;
                    }
                    shoulders.add(pos);
                }
            }
        }
        return shoulders;
    }

    // A neighboring wall fall may still be dry during decoration. Its source can be above the bridge or beside
    // the column where it leaves the wall. Stop at a ceiling that already closes the column to falling fluid.
    static boolean mayCarryFall(WorldGenLevel level, ChunkPos chunk, BlockPos base) {
        var pos = new BlockPos.MutableBlockPos();
        var beside = new BlockPos.MutableBlockPos();
        for (int y = base.getY(); y < level.getMaxBuildHeight(); y++) {
            pos.set(base.getX(), y, base.getZ());
            var state = level.getBlockState(pos);
            if (state.getBlock() instanceof LiquidBlock) {
                return true;
            }
            if (!state.isAir() && !state.canBeReplaced()) {
                return false;
            }
            for (Direction side : Direction.Plane.HORIZONTAL) {
                beside.setWithOffset(pos, side);
                if (inRegion(level, chunk, beside) && level.getBlockState(beside).getBlock() instanceof LiquidBlock) {
                    return true;
                }
            }
        }
        return false;
    }

    private static TreeMap<Integer, BlockPos> rearEdge(LongSet columns, Direction side) {
        boolean alongX = side.getAxis() == Direction.Axis.X;
        var edge = new TreeMap<Integer, BlockPos>();
        for (long cell : columns) {
            BlockPos pos = BlockPos.of(cell);
            int lane = alongX ? pos.getZ() : pos.getX();
            BlockPos previous = edge.get(lane);
            if (previous == null || (pos.getX() - previous.getX()) * side.getStepX()
                    + (pos.getZ() - previous.getZ()) * side.getStepZ() > 0) {
                edge.put(lane, pos);
            }
        }
        return edge;
    }

    // A sloping wall can meet the floor from below even where the rock at the back is too far away.
    private static WallBowlSupport fitBelow(WorldGenLevel level, RavineFootprint footprint, ChunkPos chunk, LongSet water,
            List<BlockPos> dish, double clearance) {
        var footings = new Long2ObjectOpenHashMap<Footing>();
        for (long cell : water) {
            var blocks = new ArrayList<BlockPos>();
            BlockPos anchor = anchor(level, footprint, chunk, BlockPos.of(cell).below(), Direction.DOWN, LEDGE_REACH, clearance, blocks);
            if (anchor != null) {
                footings.put(cell, new Footing(blocks, anchor));
            }
        }
        for (long cell : water) {
            BlockPos start = BlockPos.of(cell);
            for (int width = 2; width <= 3; width++) {
                WallBowlSupport ledge = ledgeAt(footings, start, width, dish);
                if (ledge != null) {
                    return ledge;
                }
            }
        }
        return null;
    }

    private static WallBowlSupport ledgeAt(Long2ObjectOpenHashMap<Footing> footings, BlockPos start, int width, List<BlockPos> dish) {
        var blocks = new ArrayList<BlockPos>();
        var anchors = new ArrayList<BlockPos>(6);
        int highest = Integer.MIN_VALUE;
        int lowest = Integer.MAX_VALUE;
        for (int dx = 0; dx < width; dx++) {
            for (int dz = 0; dz < 5 - width; dz++) {
                Footing footing = footings.get(BlockPos.asLong(start.getX() + dx, start.getY(), start.getZ() + dz));
                if (footing == null) {
                    return null;
                }
                blocks.addAll(footing.blocks());
                anchors.add(footing.anchor());
                highest = Math.max(highest, footing.anchor().getY());
                lowest = Math.min(lowest, footing.anchor().getY());
            }
        }
        if (highest - lowest > 2) {
            return null;
        }
        blocks.removeAll(dish);
        return new WallBowlSupport(blocks, anchors, start.getY() - 1 - lowest, Direction.DOWN);
    }

    private static Lane trace(WorldGenLevel level, RavineFootprint footprint, ChunkPos chunk, BlockPos start, Direction direction,
            int pool, double clearance) {
        var blocks = new ArrayList<BlockPos>();
        BlockPos upper = anchor(level, footprint, chunk, start.atY(pool - 1), direction, MAX_REACH, clearance, blocks);
        if (upper == null) {
            return null;
        }
        BlockPos lower = anchor(level, footprint, chunk, start.atY(pool - 2), direction, MAX_REACH, clearance, blocks);
        if (lower == null || normalDistance(upper, lower, direction.getAxis() == Direction.Axis.X) > 1) {
            return null;
        }
        boolean alongX = direction.getAxis() == Direction.Axis.X;
        return new Lane(blocks, upper, lower, Math.max(normalDistance(start, upper, alongX), normalDistance(start, lower, alongX)));
    }

    private static BlockPos anchor(WorldGenLevel level, RavineFootprint footprint, ChunkPos chunk, BlockPos start,
            Direction direction, int maxReach, double clearance, List<BlockPos> blocks) {
        for (int distance = 0; distance <= maxReach; distance++) {
            BlockPos pos = start.relative(direction, distance);
            if (!inRegion(level, chunk, pos) || footprint.clearanceAt(pos.getX(), pos.getY(), pos.getZ(), clearance) < clearance) {
                return null;
            }
            var state = level.getBlockState(pos);
            if (wallRock(level, footprint, pos, state)) {
                return pos;
            }
            if (!canBuild(level, footprint, pos)) {
                return null;
            }
            blocks.add(pos);
        }
        return null;
    }

    private static boolean joins(Lane a, Lane b, boolean alongX) {
        return a != null && b != null && normalDistance(a.upperAnchor(), b.upperAnchor(), alongX) <= 1
                && normalDistance(a.lowerAnchor(), b.lowerAnchor(), alongX) <= 1;
    }

    private static int normalDistance(BlockPos a, BlockPos b, boolean alongX) {
        return alongX ? Math.abs(a.getX() - b.getX()) : Math.abs(a.getZ() - b.getZ());
    }

    private static void taper(WorldGenLevel level, RavineFootprint footprint, ChunkPos chunk, Lane lane, Direction towardWall,
            double clearance, List<BlockPos> blocks) {
        Direction outward = towardWall.getOpposite();
        for (int depth = 1; depth <= 2; depth++) {
            for (int outwardStep = 1; outwardStep <= 3 - depth; outwardStep++) {
                BlockPos pos = lane.lowerAnchor().below(depth).relative(outward, outwardStep);
                if (!lane.blocks().contains(pos.above(depth)) || !inRegion(level, chunk, pos) || !canBuild(level, footprint, pos)
                        || footprint.clearanceAt(pos.getX(), pos.getY(), pos.getZ(), clearance) < clearance) {
                    return;
                }
                blocks.add(pos);
            }
        }
    }

    static boolean wallRock(WorldGenLevel level, RavineFootprint footprint, BlockPos pos, BlockState state) {
        return state.isSolidRender(level, pos) && state.getFluidState().isEmpty() && !state.hasBlockEntity()
                && !(state.getBlock() instanceof FallingBlock) && footprint.isTerrainAt(pos.getX(), pos.getY(), pos.getZ());
    }

    static boolean inRegion(WorldGenLevel level, ChunkPos chunk, BlockPos pos) {
        return !level.isOutsideBuildHeight(pos) && Math.abs((pos.getX() >> 4) - chunk.x) <= 1
                && Math.abs((pos.getZ() >> 4) - chunk.z) <= 1;
    }

    static boolean canBuild(WorldGenLevel level, RavineFootprint footprint, BlockPos pos) {
        var state = level.getBlockState(pos);
        return state.getFluidState().isEmpty() && !state.hasBlockEntity() && (state.isAir() || state.canBeReplaced())
                && footprint.isOpen(pos.getX(), pos.getY(), pos.getZ());
    }
}
