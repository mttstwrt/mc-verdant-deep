package dev.syrval.verdantdeep.basilisk;

import java.util.OptionalDouble;

import dev.syrval.verdantdeep.basilisk.BasiliskTuning.Dial;
import dev.syrval.verdantdeep.basilisk.entity.Basilisk;
import dev.syrval.verdantdeep.compat.RavineFootprintHolder;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.phys.AABB;

/**
 * Natural spawn rule of the basilisk: only in a hole's open air from just under the mouth's edge down, more likely toward the
 * floor, never near another. The surface outside the hole, and the bowl above the mouth's edge, stay clear of them. Whether the
 * whole body fits is checked afterwards by {@code Basilisk.checkSpawnObstruction}, once its size is known.
 */
public final class BasiliskSpawning {
    /** Blocks outside the hole's open air that still count as in it: the spawn block is the air over a disc's ground. */
    private static final double MARGIN = 1.0;

    private BasiliskSpawning() {}

    public static boolean canSpawn(EntityType<Basilisk> type, ServerLevelAccessor accessor, MobSpawnType spawnType, BlockPos pos, RandomSource random) {
        if (accessor.getDifficulty() == Difficulty.PEACEFUL) {
            return false;
        }
        if (!Mob.checkMobSpawnRules(type, accessor, spawnType, pos, random)) {
            return false;
        }
        if (spawnType != MobSpawnType.NATURAL && spawnType != MobSpawnType.CHUNK_GENERATION) {
            return true;
        }
        BasiliskTuning tuning = BasiliskTuning.get();
        ServerLevel level = accessor.getLevel();
        OptionalDouble depth = RavineFootprintHolder.of(level).rimDepthShareIn(pos.getX(), pos.getY(), pos.getZ(), tuning.get(Dial.SPAWN_BELOW_RIM), MARGIN);
        if (depth.isEmpty() || random.nextDouble() >= chance(tuning, depth.getAsDouble())) {
            return false;
        }
        double spacing = tuning.get(Dial.SPAWN_SPACING);
        return spacing <= 0 || level.getEntitiesOfClass(Basilisk.class, new AABB(pos).inflate(spacing)).isEmpty();
    }

    /** Chance of keeping a spawn attempt at a depth share under the rim: the rim's chance at 0, easing to the floor's at 1. */
    static double chance(BasiliskTuning tuning, double depth) {
        double t = Math.clamp(depth, 0.0, 1.0);
        double eased = t * t * (3.0 - 2.0 * t);
        return tuning.get(Dial.SPAWN_CHANCE_RIM) + (tuning.get(Dial.SPAWN_CHANCE_FLOOR) - tuning.get(Dial.SPAWN_CHANCE_RIM)) * eased;
    }
}
