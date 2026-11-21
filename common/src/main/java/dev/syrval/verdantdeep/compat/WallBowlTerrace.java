package dev.syrval.verdantdeep.compat;

import dev.syrval.verdantdeep.ravine.RavineFootprint;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;

/** A dry shelf that follows the rock beside a water catch. Each row keys into the existing wall. */
record WallBowlTerrace(List<BlockPos> blocks, List<BlockPos> walkable) {
    static final WallBowlTerrace NONE = new WallBowlTerrace(List.of(), List.of());
    private static final int LENGTH = 7;
    private static final int CUP_LENGTH = 4;
    private static final int MIN_LENGTH = 3;
    private static final int WIDTH = 3;

    static WallBowlTerrace fit(WorldGenLevel level, RavineFootprint footprint, ChunkPos chunk, LongSet outline,
            List<BlockPos> rim, int height, Direction wall, double clearance) {
        if (wall != Direction.DOWN) {
            WallBowlTerrace preferred = alongWallFace(level, footprint, chunk, outline, rim, height, wall, clearance);
            if (!preferred.blocks().isEmpty()) {
                return preferred;
            }
        }
        WallBowlTerrace best = NONE;
        // A re-entrant cliff can turn a corner at the end of the cup.
        for (Direction side : Direction.Plane.HORIZONTAL) {
            if (side == wall) {
                continue;
            }
            WallBowlTerrace candidate = alongWallFace(level, footprint, chunk, outline, rim, height, side, clearance);
            if (candidate.walkable().size() > best.walkable().size()) {
                best = candidate;
            }
        }
        if (best.blocks().isEmpty()) {
            // The cup already has a broad footing. A shorter landing can carry through its rim at a cleft in the wall.
            for (Direction side : Direction.Plane.HORIZONTAL) {
                WallBowlTerrace candidate = alongWall(level, footprint, chunk, outline, rim, height, side.getClockWise(), side, clearance, false);
                if (candidate.walkable().size() > best.walkable().size()) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    private static WallBowlTerrace alongWallFace(WorldGenLevel level, RavineFootprint footprint, ChunkPos chunk, LongSet outline,
            List<BlockPos> rim, int height, Direction wall, double clearance) {
        Direction along = wall.getClockWise();
        WallBowlTerrace first = alongWall(level, footprint, chunk, outline, rim, height, wall, along, clearance, true);
        WallBowlTerrace second = alongWall(level, footprint, chunk, outline, rim, height, wall, along.getOpposite(), clearance, true);
        return second.walkable().size() > first.walkable().size() ? second : first;
    }

    private static WallBowlTerrace alongWall(WorldGenLevel level, RavineFootprint footprint, ChunkPos chunk,
            LongSet outline, List<BlockPos> rim, int height, Direction wall, Direction along, double clearance, boolean needAnchor) {
        List<BlockPos> edge = new ArrayList<>();
        int end = Integer.MIN_VALUE;
        for (long cell : outline) {
            BlockPos lip = BlockPos.of(cell).atY(height);
            int offset = along(lip, along);
            if (offset > end) {
                edge.clear();
                end = offset;
            }
            if (offset == end && headroom(level, chunk, lip)
                    && (rim.contains(lip) || WallBowlSupport.wallRock(level, footprint, lip, level.getBlockState(lip)))) {
                edge.add(lip);
            }
        }
        if (edge.isEmpty()) {
            return NONE;
        }
        List<BlockPos> blocks = new ArrayList<>();
        List<BlockPos> walkable = new ArrayList<>();
        int addedFloor = 0;
        int length = needAnchor ? LENGTH : CUP_LENGTH;
        for (int step = 1; step <= length; step++) {
            List<BlockPos> row = findRow(level, footprint, chunk, edge.getFirst().relative(along), wall, along, edge, clearance,
                    needAnchor && step == 1 ? 1 : 2, step > 1 && step < length ? WIDTH + 1 : WIDTH, needAnchor);
            if (row.isEmpty()) {
                break;
            }
            walkable.addAll(row);
            for (int i = 0; i < row.size(); i++) {
                BlockPos floor = row.get(i);
                if (isWall(level, footprint, chunk, floor)) {
                    continue;
                }
                addedFloor++;
                blocks.add(floor);
                // Near the wall the rock steps down to carry the shelf. The exposed edge stays thin.
                int thickness = needAnchor ? 2 - i : Math.max(0, 3 - step);
                for (int down = 1; down <= thickness; down++) {
                    BlockPos lower = floor.below(down);
                    if (!canPlace(level, footprint, chunk, lower, clearance)) {
                        break;
                    }
                    blocks.add(lower);
                }
            }
            edge = row;
        }
        return walkable.size() >= MIN_LENGTH * WIDTH && addedFloor >= 2 * WIDTH ? new WallBowlTerrace(blocks, walkable) : NONE;
    }

    private static List<BlockPos> findRow(WorldGenLevel level, RavineFootprint footprint, ChunkPos chunk, BlockPos column,
            Direction wall, Direction along, List<BlockPos> previous, double clearance, int overlap, int width, boolean needAnchor) {
        Direction outward = wall.getOpposite();
        for (int offset = -4; offset <= 6; offset++) {
            BlockPos anchor = column.relative(wall, offset);
            if (needAnchor && !isWall(level, footprint, chunk, anchor)) {
                BlockPos footing = anchor.relative(outward).below();
                if (!isWall(level, footprint, chunk, anchor.below())
                        || !(isWall(level, footprint, chunk, footing) || canPlace(level, footprint, chunk, footing, clearance))) {
                    continue;
                }
            }
            List<BlockPos> row = new ArrayList<>(width);
            int shared = 0;
            for (int distance = 1; distance <= width; distance++) {
                BlockPos floor = anchor.relative(outward, distance);
                if (!headroom(level, chunk, floor)
                        || !(isWall(level, footprint, chunk, floor) || canPlace(level, footprint, chunk, floor, clearance))) {
                    break;
                }
                row.add(floor);
                if (previous.contains(floor.relative(along.getOpposite()))) {
                    shared++;
                }
            }
            if (row.size() >= WIDTH && shared >= overlap) {
                return row;
            }
        }
        return List.of();
    }

    private static boolean isWall(WorldGenLevel level, RavineFootprint footprint, ChunkPos chunk, BlockPos pos) {
        return WallBowlSupport.inRegion(level, chunk, pos) && WallBowlSupport.wallRock(level, footprint, pos, level.getBlockState(pos));
    }

    private static int along(BlockPos pos, Direction side) {
        return pos.getX() * side.getStepX() + pos.getZ() * side.getStepZ();
    }

    private static boolean headroom(WorldGenLevel level, ChunkPos chunk, BlockPos floor) {
        for (int up = 1; up <= 2; up++) {
            BlockPos pos = floor.above(up);
            if (!WallBowlSupport.inRegion(level, chunk, pos)) {
                return false;
            }
            var state = level.getBlockState(pos);
            if (!state.getFluidState().isEmpty() || !state.getCollisionShape(level, pos).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static boolean canPlace(WorldGenLevel level, RavineFootprint footprint, ChunkPos chunk, BlockPos pos, double clearance) {
        return WallBowlSupport.inRegion(level, chunk, pos)
                && WallBowlSupport.canBuild(level, footprint, pos)
                && footprint.clearanceAt(pos.getX(), pos.getY(), pos.getZ(), clearance) >= clearance
                && !WallBowlSupport.mayCarryFall(level, chunk, pos);
    }
}
