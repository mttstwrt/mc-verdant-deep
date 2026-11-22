package dev.syrval.verdantdeep.compat;

import dev.syrval.verdantdeep.ravine.RavineFootprint;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.GrowingPlantBlock;
import net.minecraft.world.level.block.LiquidBlock;

/**
 * A dry shelf that runs along the chasm wall from the rim of a water catch. Every lane starts at the wall face, so the
 * shelf follows the wall's curve, and the rock under it deepens toward the face like a bracket.
 */
record WallBowlTerrace(List<BlockPos> blocks, List<BlockPos> walkable) {
    static final WallBowlTerrace NONE = new WallBowlTerrace(List.of(), List.of());
    private static final int MIN_LANES = 3;
    private static final int MIN_FLOOR = 12;
    private static final int MIN_DEPTH = 2;
    private static final int MIN_LENGTH = 7;
    private static final int MAX_LENGTH = 11;
    private static final int MIN_FULL_DEPTH = 4;
    private static final int MAX_FULL_DEPTH = 6;
    // How far a lane looks for the face, how many cells under an overhang it fills before the floor, and how much
    // further than its bracket the underside may reach to meet a slope below.
    private static final int FACE_SEARCH = 5;
    private static final int MAX_TUCK = 3;
    private static final int JOIN_SEARCH = 4;

    /** What the shelf keeps clear of besides discs, roots and falls: room round drops that may still come. */
    record Room(LongSet caughtFluid, int reach, int searchHeight) {}

    static WallBowlTerrace fit(WorldGenLevel level, RavineFootprint footprint, ChunkPos chunk, LongSet outline,
            List<BlockPos> rim, int height, Direction wall, double clearance, Room room) {
        WallBowlTerrace best = NONE;
        // Other faces can fit when the wall turns a corner beside the cup.
        for (Direction side : Direction.Plane.HORIZONTAL) {
            for (Direction along : List.of(side.getClockWise(), side.getCounterClockWise())) {
                WallBowlTerrace candidate = alongFace(level, footprint, chunk, outline, rim, height, side, along, clearance, room);
                int size = candidate.walkable().size();
                if (size > best.walkable().size() || (side == wall && size > 0 && size == best.walkable().size())) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    private static WallBowlTerrace alongFace(WorldGenLevel level, RavineFootprint footprint, ChunkPos chunk, LongSet outline,
            List<BlockPos> rim, int height, Direction wall, Direction along, double clearance, Room room) {
        LongSet edge = cupEdge(level, footprint, chunk, outline, rim, height, along);
        if (edge.isEmpty()) {
            return NONE;
        }
        // Leave from the part of the cup's lip nearest the wall, where the face is.
        BlockPos rear = null;
        for (long cell : edge) {
            BlockPos lip = BlockPos.of(cell);
            if (rear == null || along(lip, wall) > along(rear, wall)) {
                rear = lip;
            }
        }
        RandomSource random = RandomSource.create(level.getSeed() ^ Mth.getSeed(rear) ^ along.get2DDataValue());
        int length = random.nextIntBetweenInclusive(MIN_LENGTH, MAX_LENGTH);
        int fullDepth = random.nextIntBetweenInclusive(MIN_FULL_DEPTH, MAX_FULL_DEPTH);
        int tail = Math.max(2, length / 3);
        Direction outward = wall.getOpposite();

        List<BlockPos> blocks = new ArrayList<>();
        List<BlockPos> walkable = new ArrayList<>();
        LongSet probed = new LongOpenHashSet();
        LongSet previous = edge;
        BlockPos column = rear.relative(along);
        int lastDepth = fullDepth;
        int lanes = 0;
        for (int lane = 1; lane <= length; lane++) {
            int wobble = random.nextInt(3) - 1;
            BlockPos back = face(level, footprint, chunk, column, wall);
            if (back == null || (lane > 1 && Math.abs(along(back, wall) - along(column, wall)) > 1)) {
                break;
            }
            // Full depth by the cup, narrowing over the far third, with a ragged front.
            int fromEnd = length - lane;
            int base = fromEnd >= tail ? fullDepth : MIN_DEPTH + (fullDepth - MIN_DEPTH) * fromEnd / tail;
            int target = Mth.clamp(base + wobble, Math.max(MIN_DEPTH, lastDepth - 1), lastDepth + 1);
            List<BlockPos> floor = new ArrayList<>(MAX_TUCK + target);
            int tucked = laneFloor(level, footprint, chunk, back, outward, target, clearance, room, floor);
            List<BlockPos> walk = floor.subList(tucked, floor.size());
            int joined = 0;
            for (BlockPos cell : walk) {
                if (previous.contains(cell.relative(along.getOpposite()).asLong())) {
                    joined++;
                }
            }
            // A narrow cup end may meet the first lane in a single block; lanes after it share at least two.
            if (walk.size() < MIN_DEPTH || joined < Math.min(lane == 1 ? 1 : 2, previous.size())) {
                break;
            }
            List<BlockPos> laneBlocks = new ArrayList<>(floor);
            for (int distance = 0; distance < floor.size(); distance++) {
                bracket(level, footprint, chunk, floor.get(distance), distance, floor.size(), clearance, room, laneBlocks);
            }
            if (!leavesRoom(level, chunk, laneBlocks, room, probed)) {
                break;
            }
            blocks.addAll(laneBlocks);
            LongSet laneCells = new LongOpenHashSet(walk.size());
            for (BlockPos cell : walk) {
                laneCells.add(cell.asLong());
            }
            walkable.addAll(walk);
            previous = laneCells;
            lastDepth = walk.size();
            column = back.relative(along);
            lanes++;
        }
        return lanes >= MIN_LANES && walkable.size() >= MIN_FLOOR ? new WallBowlTerrace(blocks, walkable) : NONE;
    }

    // The lane's rock at floor height from the face outward, into floor; returns how many of its first cells lack
    // headroom. Under an overhanging face those are filled too, so the floor still meets the face.
    private static int laneFloor(WorldGenLevel level, RavineFootprint footprint, ChunkPos chunk, BlockPos back,
            Direction outward, int walkableDepth, double clearance, Room room, List<BlockPos> floor) {
        int tucked = 0;
        for (int distance = 0; floor.size() - tucked < walkableDepth; distance++) {
            BlockPos cell = back.relative(outward, distance);
            if (!canPlace(level, footprint, chunk, cell, clearance, room)) {
                break;
            }
            if (!headroom(level, chunk, cell)) {
                if (floor.size() > tucked || tucked == MAX_TUCK) {
                    break;
                }
                tucked++;
            }
            floor.add(cell);
        }
        return tucked;
    }

    // The cup's lip on the side the shelf leaves from: the walkable rim or native rock of its outermost line.
    private static LongSet cupEdge(WorldGenLevel level, RavineFootprint footprint, ChunkPos chunk, LongSet outline,
            List<BlockPos> rim, int height, Direction along) {
        LongSet edge = new LongOpenHashSet();
        int end = Integer.MIN_VALUE;
        for (long cell : outline) {
            BlockPos lip = BlockPos.of(cell).atY(height);
            int offset = along(lip, along);
            if (offset > end) {
                edge.clear();
                end = offset;
            }
            if (offset == end && headroom(level, chunk, lip)
                    && (rim.contains(lip) || isWall(level, footprint, chunk, lip))) {
                edge.add(lip.asLong());
            }
        }
        return edge;
    }

    // The open cell in front of the wall on this lane: step out of rock, or in toward it, a few blocks at most.
    private static BlockPos face(WorldGenLevel level, RavineFootprint footprint, ChunkPos chunk, BlockPos column, Direction wall) {
        BlockPos pos = column;
        if (isWall(level, footprint, chunk, pos)) {
            for (int step = 0; step < FACE_SEARCH; step++) {
                pos = pos.relative(wall.getOpposite());
                if (!isWall(level, footprint, chunk, pos)) {
                    return pos;
                }
            }
            return null;
        }
        for (int step = 0; step < FACE_SEARCH; step++) {
            BlockPos next = pos.relative(wall);
            if (isWall(level, footprint, chunk, next)) {
                return pos;
            }
            pos = next;
        }
        return null;
    }

    // Rock under the floor, deepest at the face and gone at the front. Near the face it carries on down to a slope
    // it can reach, so the shelf grows out of the wall instead of hanging off it.
    private static void bracket(WorldGenLevel level, RavineFootprint footprint, ChunkPos chunk, BlockPos floor, int distance,
            int depth, double clearance, Room room, List<BlockPos> blocks) {
        double rest = 1.0 - (double) distance / depth;
        int under = (int) Math.round((depth + 1) * rest * rest);
        if (under == 0) {
            return;
        }
        int start = blocks.size();
        for (int down = 1; down <= under + JOIN_SEARCH; down++) {
            BlockPos lower = floor.below(down);
            if (isWall(level, footprint, chunk, lower)) {
                return;
            }
            if (!canPlace(level, footprint, chunk, lower, clearance, room)) {
                break;
            }
            blocks.add(lower);
        }
        // No slope in reach: keep the bracket's own depth.
        if (blocks.size() - start > under) {
            blocks.subList(start + under, blocks.size()).clear();
        }
    }

    // Another chunk may still be looking for a basin. Keep the shelf out of the space beside drops that may still come.
    private static boolean leavesRoom(WorldGenLevel level, ChunkPos chunk, List<BlockPos> blocks, Room room, LongSet probed) {
        var probe = new BlockPos.MutableBlockPos();
        for (BlockPos rock : blocks) {
            int ceiling = Math.min(level.getMaxBuildHeight(), rock.getY() + room.searchHeight() + 1);
            for (int dx = -room.reach(); dx <= room.reach(); dx++) {
                for (int dz = -room.reach(); dz <= room.reach(); dz++) {
                    probe.set(rock.getX() + dx, rock.getY(), rock.getZ() + dz);
                    if (probed.add(probe.asLong()) && mayFeedFall(level, chunk, probe, room.caughtFluid(), ceiling)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    // Like WallBowlSupport.mayCarryFall, but fluid already caught, or held on every side and below, starts no fall.
    private static boolean mayFeedFall(WorldGenLevel level, ChunkPos chunk, BlockPos base, LongSet caught, int ceiling) {
        var pos = new BlockPos.MutableBlockPos();
        var beside = new BlockPos.MutableBlockPos();
        for (int y = base.getY(); y < ceiling; y++) {
            pos.set(base.getX(), y, base.getZ());
            var state = level.getBlockState(pos);
            if (state.getBlock() instanceof LiquidBlock) {
                return mayRun(level, chunk, pos, caught);
            }
            if (!state.isAir() && !state.canBeReplaced()) {
                return false;
            }
            for (Direction side : Direction.Plane.HORIZONTAL) {
                beside.setWithOffset(pos, side);
                if (WallBowlSupport.inRegion(level, chunk, beside) && level.getBlockState(beside).getBlock() instanceof LiquidBlock
                        && mayRun(level, chunk, beside, caught)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean mayRun(WorldGenLevel level, ChunkPos chunk, BlockPos fluid, LongSet caught) {
        if (caught.contains(fluid.asLong())) {
            return false;
        }
        var next = new BlockPos.MutableBlockPos();
        for (Direction side : Direction.values()) {
            if (side == Direction.UP) {
                continue;
            }
            next.setWithOffset(fluid, side);
            if (!WallBowlSupport.inRegion(level, chunk, next)) {
                return true;
            }
            var state = level.getBlockState(next);
            if (state.getFluidState().isEmpty() && (state.isAir() || state.canBeReplaced())) {
                return true;
            }
        }
        return false;
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

    private static boolean canPlace(WorldGenLevel level, RavineFootprint footprint, ChunkPos chunk, BlockPos pos, double clearance,
            Room room) {
        return canSeeAround(chunk, pos, room.reach())
                && !level.isOutsideBuildHeight(pos)
                && canClear(level, footprint, pos)
                && footprint.clearanceAt(pos.getX(), pos.getY(), pos.getZ(), clearance) >= clearance
                && !WallBowlSupport.mayCarryFall(level, chunk, pos);
    }

    // Like the cup, the shelf may build over the wall's small decoration.
    private static boolean canClear(WorldGenLevel level, RavineFootprint footprint, BlockPos pos) {
        var state = level.getBlockState(pos);
        return WallBowlSupport.canBuild(level, footprint, pos) || (state.getFluidState().isEmpty()
                && (state.is(Blocks.POINTED_DRIPSTONE) || state.getBlock() instanceof GrowingPlantBlock)
                && footprint.isOpen(pos.getX(), pos.getY(), pos.getZ()));
    }

    // The room check reads the columns within reach of the shelf, and the fall check the ones beside it, all inside the
    // writable 3x3 chunks.
    private static boolean canSeeAround(ChunkPos chunk, BlockPos pos, int reach) {
        int margin = Math.max(1, reach) + 1;
        int x = pos.getX() - chunk.getMinBlockX();
        int z = pos.getZ() - chunk.getMinBlockZ();
        return x >= -16 + margin && x < 32 - margin && z >= -16 + margin && z < 32 - margin;
    }
}
