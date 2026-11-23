package dev.syrval.verdantdeep.compat;

import dev.syrval.verdantdeep.VerdantDeep;
import dev.syrval.verdantdeep.ravine.RavineFootprint;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

final class WallBowlLip {
    private static final BlockState SOIL = Blocks.COARSE_DIRT.defaultBlockState();

    private WallBowlLip() {}

    static void plant(WorldGenLevel level, RavineFootprint footprint, ChunkPos chunk, BlockPos pool, List<BlockPos> rim,
            LongSet outline, int top, BlockState rock, double clearance, int fireClearance) {
        List<BlockState> plants = footprint.bowlPlantsAt(pool.getX(), pool.getY(), pool.getZ());
        if (plants.isEmpty()) {
            return;
        }
        RandomSource random = RandomSource.create(level.getSeed() ^ Mth.getSeed(pool));
        var tip = new ArrayList<BlockPos>();
        var planted = new ArrayList<BlockPos>();
        var seen = new LongOpenHashSet();
        for (BlockPos bank : rim) {
            if (bank.getY() != top || !level.getBlockState(bank).is(rock.getBlock())) {
                continue;
            }
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos bed = bank.relative(side);
                if (outline.contains(bed.atY(pool.getY()).asLong()) || !seen.add(bed.asLong())
                        || !space(level, footprint, chunk, bed, clearance)
                        || !WallBowlSupport.inRegion(level, chunk, bed.above()) || !level.isEmptyBlock(bed.above())
                        || footprint.clearanceAt(bed.getX(), bed.getY() + 1, bed.getZ(), clearance) < clearance) {
                    continue;
                }
                BlockPos heel = bed.below();
                if (!WallBowlSupport.inRegion(level, chunk, heel)) {
                    continue;
                }
                var support = level.getBlockState(heel);
                boolean fillHeel = support.isAir();
                if (fillHeel && !space(level, footprint, chunk, heel, clearance)) {
                    continue;
                }
                if ((!fillHeel && (!support.isSolidRender(level, heel) || !support.getFluidState().isEmpty()))
                        || WallBowlSupport.mayCarryFall(level, chunk, fillHeel ? heel : bed)) {
                    continue;
                }
                level.setBlock(bed, rock, Block.UPDATE_CLIENTS);
                if (fillHeel) {
                    level.setBlock(heel, rock, Block.UPDATE_CLIENTS);
                }
                tip.add(bed);
                if (random.nextInt(4) == 0) {
                    BlockState plant = plants.get(random.nextInt(plants.size()));
                    BlockPos above = bed.above();
                    if (!plant.getFluidState().isEmpty() || plant.hasBlockEntity()
                            || !plant.getCollisionShape(level, above).isEmpty()
                            || (plant.ignitedByLava() && nearLava(level, chunk, above, fireClearance))) {
                        continue;
                    }
                    level.setBlock(bed, SOIL, Block.UPDATE_CLIENTS);
                    if (plant.canSurvive(level, above)) {
                        level.setBlock(above, plant, Block.UPDATE_CLIENTS);
                        planted.add(above);
                    } else {
                        level.setBlock(bed, rock, Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
        if (!tip.isEmpty()) {
            VerdantDeep.LOGGER.debug("Wall bowl lip at {} beds={} plants={}", pool.toShortString(), tip, planted);
        }
    }

    private static boolean space(WorldGenLevel level, RavineFootprint footprint, ChunkPos chunk, BlockPos pos, double clearance) {
        return WallBowlSupport.inRegion(level, chunk, pos) && level.isEmptyBlock(pos)
                && footprint.isOpen(pos.getX(), pos.getY(), pos.getZ())
                && footprint.clearanceAt(pos.getX(), pos.getY(), pos.getZ(), clearance) >= clearance;
    }

    private static boolean nearLava(WorldGenLevel level, ChunkPos chunk, BlockPos plant, int reach) {
        var probe = new BlockPos.MutableBlockPos();
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                for (int dy = -reach; dy <= reach; dy++) {
                    probe.setWithOffset(plant, dx, dy, dz);
                    if (!WallBowlSupport.inRegion(level, chunk, probe) || level.getFluidState(probe).is(FluidTags.LAVA)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
