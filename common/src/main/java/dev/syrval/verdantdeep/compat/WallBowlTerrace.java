package dev.syrval.verdantdeep.compat;

import dev.syrval.verdantdeep.ravine.RavineFootprint;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;

/** A dry shelf connected to the solid wall or the rim of an anchored water catch. */
record WallBowlTerrace(List<BlockPos> blocks, List<BlockPos> walkable) {
    static final WallBowlTerrace NONE = new WallBowlTerrace(List.of(), List.of());
    private static final int BROAD_MIN_AREA = 30;

    private enum Profile {
        WALL_SHELF(9, 7, 5, 3, BROAD_MIN_AREA, true),
        CUP_SHELF(6, 9, 5, 4, BROAD_MIN_AREA, false),
        WALL_LEDGE(7, 4, 3, 1, 9, true),
        CUP_LANDING(4, 4, 3, 2, 9, false);

        final int length;
        final int width;
        final int minWidth;
        final int entryOverlap;
        final int minArea;
        final boolean wallAnchor;

        Profile(int length, int width, int minWidth, int entryOverlap, int minArea, boolean wallAnchor) {
            this.length = length;
            this.width = width;
            this.minWidth = minWidth;
            this.entryOverlap = entryOverlap;
            this.minArea = minArea;
            this.wallAnchor = wallAnchor;
        }
    }

    static WallBowlTerrace fit(WorldGenLevel level, RavineFootprint footprint, ChunkPos chunk, LongSet outline,
            List<BlockPos> rim, int height, Direction wall, double clearance, LongSet ownSources, int receiverReach, int searchHeight) {
        for (Profile profile : Profile.values()) {
            if (profile.minArea < BROAD_MIN_AREA) {
                break;
            }
            WallBowlTerrace best = NONE;
            // Other faces can fit when the wall turns a corner beside the cup.
            for (Direction side : Direction.Plane.HORIZONTAL) {
                Direction along = side.getClockWise();
                WallBowlTerrace first = alongWall(level, footprint, chunk, outline, rim, height, side, along, clearance, profile);
                WallBowlTerrace second = profile.wallAnchor
                        ? alongWall(level, footprint, chunk, outline, rim, height, side, along.getOpposite(), clearance, profile) : NONE;
                for (WallBowlTerrace candidate : List.of(first, second)) {
                    if ((candidate.walkable().size() > best.walkable().size()
                            || (side == wall && candidate.walkable().size() == best.walkable().size()))
                            && candidate.leavesRoomForCatches(level, chunk, ownSources, receiverReach, searchHeight)) {
                        best = candidate;
                    }
                }
            }
            if (!best.blocks().isEmpty()) {
                return best;
            }
        }
        return compact(level, footprint, chunk, outline, rim, height, wall, clearance, ownSources, receiverReach, searchHeight);
    }

    private static WallBowlTerrace compact(WorldGenLevel level, RavineFootprint footprint, ChunkPos chunk, LongSet outline,
            List<BlockPos> rim, int height, Direction wall, double clearance, LongSet ownSources, int receiverReach, int searchHeight) {
        WallBowlTerrace best = NONE;
        for (Direction face : Direction.Plane.HORIZONTAL) {
            Direction along = face.getClockWise();
            WallBowlTerrace first = alongWall(level, footprint, chunk, outline, rim, height, face, along, clearance, Profile.WALL_LEDGE);
            WallBowlTerrace second = alongWall(level, footprint, chunk, outline, rim, height, face, along.getOpposite(), clearance, Profile.WALL_LEDGE);
            if (!first.leavesRoomForCatches(level, chunk, ownSources, receiverReach, searchHeight)) {
                first = NONE;
            }
            if (!second.leavesRoomForCatches(level, chunk, ownSources, receiverReach, searchHeight)) {
                second = NONE;
            }
            WallBowlTerrace candidate = second.walkable().size() > first.walkable().size() ? second : first;
            if (face == wall && !candidate.blocks().isEmpty()) {
                return candidate;
            }
            if (candidate.walkable().size() > best.walkable().size()) {
                best = candidate;
            }
        }
        if (best.blocks().isEmpty()) {
            for (Direction face : Direction.Plane.HORIZONTAL) {
                WallBowlTerrace candidate = alongWall(level, footprint, chunk, outline, rim, height, face, face.getClockWise(),
                        clearance, Profile.CUP_LANDING);
                if (candidate.walkable().size() > best.walkable().size()
                        && candidate.leavesRoomForCatches(level, chunk, ownSources, receiverReach, searchHeight)) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    // Another chunk may still be looking for a basin. Reserve the space beside nearby drops even while the wall is dry.
    private boolean leavesRoomForCatches(WorldGenLevel level, ChunkPos chunk, LongSet ownSources, int reach, int searchHeight) {
        var checked = new LongOpenHashSet();
        var probe = new BlockPos.MutableBlockPos();
        for (BlockPos rock : blocks) {
            int ceiling = Math.min(level.getMaxBuildHeight(), rock.getY() + searchHeight + 1);
            for (int dx = -reach; dx <= reach; dx++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    probe.set(rock.getX() + dx, rock.getY(), rock.getZ() + dz);
                    if (checked.add(probe.asLong()) && (!canSeeSides(chunk, probe)
                            || WallBowlSupport.mayCarryFall(level, chunk, probe, ownSources, ceiling))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static WallBowlTerrace alongWall(WorldGenLevel level, RavineFootprint footprint, ChunkPos chunk,
            LongSet outline, List<BlockPos> rim, int height, Direction wall, Direction along, double clearance, Profile profile) {
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
        if (edge.size() < profile.entryOverlap) {
            return NONE;
        }
        boolean broad = profile.minArea >= BROAD_MIN_AREA;
        if (broad) {
            edge.sort(Comparator.comparingInt(lip -> along(lip, wall)));
        }
        List<BlockPos> blocks = new ArrayList<>();
        List<BlockPos> walkable = new ArrayList<>();
        int addedFloor = 0;
        for (int step = 1; step <= profile.length; step++) {
            int overlap = step == 1 ? profile.entryOverlap : Math.max(2, profile.minWidth - 2);
            int width = step == 1 || step == profile.length ? profile.width - 1 : profile.width;
            BlockPos center = broad ? edge.get(edge.size() / 2) : edge.getFirst();
            List<BlockPos> row = findRow(level, footprint, chunk, center.relative(along), wall, along, edge, clearance,
                    overlap, width, profile);
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
                int thickness = profile.wallAnchor ? 2 - i : Math.max(0, profile.length - 1 - step);
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
        return walkable.size() >= profile.minArea && addedFloor >= 2 * profile.minWidth ? new WallBowlTerrace(blocks, walkable) : NONE;
    }

    private static List<BlockPos> findRow(WorldGenLevel level, RavineFootprint footprint, ChunkPos chunk, BlockPos column,
            Direction wall, Direction along, List<BlockPos> previous, double clearance, int overlap, int width, Profile profile) {
        Direction outward = wall.getOpposite();
        List<BlockPos> best = List.of();
        boolean broad = profile.minArea >= BROAD_MIN_AREA;
        // Try the center first so a wide terrace keeps a broad seam with the previous row.
        int centerOffset = width / 2 + 1;
        int attempts = broad ? 2 * (centerOffset + 4) : 10;
        for (int attempt = 0; attempt <= attempts; attempt++) {
            int offset = broad ? centerOffset + (attempt % 2 == 0 ? -attempt / 2 : (attempt + 1) / 2) : attempt - 4;
            if (broad && offset > width + 2) {
                continue;
            }
            BlockPos anchor = column.relative(wall, offset);
            if (profile.wallAnchor && !isWall(level, footprint, chunk, anchor)) {
                BlockPos footing = anchor.relative(outward).below();
                if (!isWall(level, footprint, chunk, anchor.below())
                        || !(isWall(level, footprint, chunk, footing) || canPlace(level, footprint, chunk, footing, clearance))) {
                    continue;
                }
            }
            List<BlockPos> row = new ArrayList<>(width);
            int shared = 0;
            int run = 0;
            for (int distance = 1; distance <= width; distance++) {
                BlockPos floor = anchor.relative(outward, distance);
                if (!headroom(level, chunk, floor)
                        || !(isWall(level, footprint, chunk, floor) || canPlace(level, footprint, chunk, floor, clearance))) {
                    break;
                }
                row.add(floor);
                boolean adjoining = previous.contains(floor.relative(along.getOpposite()));
                run = adjoining ? run + 1 : 0;
                shared = broad ? Math.max(shared, run) : shared + (adjoining ? 1 : 0);
            }
            if (row.size() >= profile.minWidth && shared >= overlap && row.size() > best.size()) {
                if (!broad || (row.size() == width && shared == Math.min(width, previous.size()))) {
                    return row;
                }
                best = row;
            }
        }
        return best;
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
                && canSeeSides(chunk, pos)
                && WallBowlSupport.canBuild(level, footprint, pos)
                && footprint.clearanceAt(pos.getX(), pos.getY(), pos.getZ(), clearance) >= clearance
                && !WallBowlSupport.mayCarryFall(level, chunk, pos);
    }

    // The fall check needs the carved columns immediately beside the shelf, including at a chunk boundary.
    private static boolean canSeeSides(ChunkPos chunk, BlockPos pos) {
        int x = pos.getX() - chunk.getMinBlockX();
        int z = pos.getZ() - chunk.getMinBlockZ();
        return x > -16 && x < 31 && z > -16 && z < 31;
    }
}
