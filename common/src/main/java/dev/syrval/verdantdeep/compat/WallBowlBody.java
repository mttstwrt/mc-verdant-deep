package dev.syrval.verdantdeep.compat;

import dev.syrval.verdantdeep.ravine.RavineFootprint;
import dev.syrval.verdantdeep.ravine.WallBowlSettings.Profile;
import it.unimi.dsi.fastutil.ints.Int2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;

/** Shapes the rock between a sealed catch and the cliff below it. */
final class WallBowlBody {
    private static final int WALL_SEARCH = 14;

    private record Span(int front, int back) {
        Span include(int cell) {
            return new Span(Math.min(front, cell), Math.max(back, cell));
        }
    }

    private WallBowlBody() {}

    static List<BlockPos> fit(WorldGenLevel level, RavineFootprint footprint, ChunkPos chunk, Profile profile,
            LongSet outline, LongSet shell, int pool, Direction wall, double clearance) {
        if (wall != Direction.DOWN) {
            return along(level, footprint, chunk, profile, outline, shell, pool, wall, clearance);
        }
        List<BlockPos> best = List.of();
        for (Direction side : Direction.Plane.HORIZONTAL) {
            List<BlockPos> candidate = along(level, footprint, chunk, profile, outline, shell, pool, side, clearance);
            if (candidate.size() > best.size()) {
                best = candidate;
            }
        }
        return best;
    }

    private static List<BlockPos> along(WorldGenLevel level, RavineFootprint footprint, ChunkPos chunk, Profile profile,
            LongSet outline, LongSet shell, int pool, Direction wall, double clearance) {
        var lanes = new Int2ObjectAVLTreeMap<Span>();
        boolean alongX = wall.getAxis() == Direction.Axis.X;
        int sign = alongX ? wall.getStepX() : wall.getStepZ();
        for (long cell : outline) {
            int lane = alongX ? BlockPos.getZ(cell) : BlockPos.getX(cell);
            int normal = sign * (alongX ? BlockPos.getX(cell) : BlockPos.getZ(cell));
            Span span = lanes.get(lane);
            lanes.put(lane, span == null ? new Span(normal, normal) : span.include(normal));
        }
        double centre = (lanes.firstIntKey() + lanes.lastIntKey()) * 0.5;
        double halfWidth = (lanes.lastIntKey() - lanes.firstIntKey()) * 0.5 + 1;
        var blocks = new ArrayList<BlockPos>();
        var pos = new BlockPos.MutableBlockPos();
        for (var entry : lanes.int2ObjectEntrySet()) {
            int lane = entry.getIntKey();
            double across = Math.abs(lane - centre) / halfWidth;
            Span rim = entry.getValue();
            int width = rim.back() - rim.front() + 1;
            int depth = profile == Profile.SCONCE
                    ? Math.clamp(width * 2, 8, 18)
                    : Math.clamp(width, 4, 9);
            Span previous = rim;
            for (int down = 1; down <= depth; down++) {
                double t = (double) (down - 1) / depth;
                // The sconce narrows in both axes. The disc bracket loses most of its projection near the lip.
                if (profile == Profile.SCONCE && across > 1 - t * t) {
                    break;
                }
                double retreat = profile == Profile.SCONCE ? t : Math.pow(t, 2.0 / 3.0);
                int y = pool - down;
                int target = rim.front() + (int) Math.round((width - 1) * retreat);
                if (profile == Profile.CANTILEVER) {
                    target += (int) Math.round(width * across * t * 0.5);
                }
                int rock = face(level, footprint, chunk, pos, alongX, sign, lane, y, target, rim.back());
                if (rock <= target) {
                    break;
                }
                // The front also retreats toward the actual wall as the wall recedes under an overhang.
                int front = Math.max(target, rock - Math.max(1, (int) Math.round((rock - rim.front()) * (1 - retreat))));
                int first = freeSpan(level, footprint, chunk, shell, clearance, pos, alongX, sign, lane, y, front, rock, down == 1 ? null : previous);
                if (first >= rock || first > previous.back() || rock < previous.front()) {
                    break;
                }
                for (int cell = first; cell < rock; cell++) {
                    at(pos, alongX, sign, lane, y, cell);
                    if (!shell.contains(pos.asLong())) {
                        blocks.add(pos.immutable());
                    }
                }
                previous = new Span(first, rock);
            }
        }
        return blocks;
    }

    private static int face(WorldGenLevel level, RavineFootprint footprint, ChunkPos chunk, BlockPos.MutableBlockPos pos,
            boolean alongX, int sign, int lane, int y, int front, int rear) {
        for (int cell = front; cell <= rear + WALL_SEARCH; cell++) {
            at(pos, alongX, sign, lane, y, cell);
            if (!WallBowlSupport.inRegion(level, chunk, pos)) {
                break;
            }
            if (WallBowlSupport.wallRock(level, footprint, pos, level.getBlockState(pos))) {
                return cell;
            }
        }
        return front;
    }

    private static int freeSpan(WorldGenLevel level, RavineFootprint footprint, ChunkPos chunk, LongSet shell, double clearance,
            BlockPos.MutableBlockPos pos, boolean alongX, int sign, int lane, int y, int front, int rock, Span above) {
        for (int cell = rock - 1; cell >= front; cell--) {
            at(pos, alongX, sign, lane, y, cell);
            if (shell.contains(pos.asLong())) {
                continue;
            }
            // Once a planned row roofs this column, fluid from above cannot fall through it.
            boolean roofed = above != null && cell >= above.front() && cell < above.back();
            if (!WallBowlSupport.canBuild(level, footprint, pos)
                    || footprint.clearanceAt(pos.getX(), y, pos.getZ(), clearance) < clearance
                    || (!roofed && WallBowlSupport.mayCarryFall(level, chunk, pos))) {
                return cell + 1;
            }
        }
        return front;
    }

    private static void at(BlockPos.MutableBlockPos pos, boolean alongX, int sign, int lane, int y, int normal) {
        pos.set(alongX ? sign * normal : lane, y, alongX ? lane : sign * normal);
    }
}
