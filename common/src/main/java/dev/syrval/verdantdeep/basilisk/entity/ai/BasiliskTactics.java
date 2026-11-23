package dev.syrval.verdantdeep.basilisk.entity.ai;

import dev.syrval.verdantdeep.basilisk.BasiliskTuning;
import dev.syrval.verdantdeep.basilisk.BasiliskTuning.Dial;
import dev.syrval.verdantdeep.basilisk.entity.Basilisk;
import dev.syrval.verdantdeep.basilisk.entity.BasiliskSize;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The basilisk's fighting plan, shared by BasiliskStalkGoal (circling between strikes) and BasiliskAttackGoal (the strikes and
 * the retreats after them): when the next strike is due, which way and how wide it circles, and where it goes to circle, to
 * retreat or to strike from. Server-side state owned by one basilisk; not saved, so a reloaded basilisk starts a fresh fight.
 *
 * <p>Points are picked from a handful of candidates. None is near a drop (TerrainProbe.isNearEdge), and with the COVER dial up
 * the ones the target cannot see are preferred, so between strikes it lurks behind trunks and bushes where there are any.
 */
public final class BasiliskTactics {
    /** Blocks of arc advanced round the target per circling step. */
    private static final double CIRCLE_ARC = 5.0D;
    private static final double[] CIRCLE_STEPS = {1.0D, 0.5D, 1.6D};
    private static final double[] CIRCLE_RADIUS_OFFSETS = {0.0D, -3.0D, 3.0D};
    /** Radians off straight away from the target that a retreat may take. */
    private static final double[] RETREAT_BEARINGS = {0.0D, 0.5D, -0.5D, 1.0D, -1.0D, 1.5D, -1.5D};
    /** Radians off the current bearing from which a strike may be lined up. */
    private static final double[] STRIKE_BEARINGS = {0.0D, 0.4D, -0.4D, 0.8D, -0.8D};
    /** Score a spot fully out of the target's sight gains, times the COVER dial. */
    private static final double COVER_SCORE = 4.0D;
    /** Blocks above and below the reference height in which a picked spot is put on the ground. */
    private static final int GROUND_SEARCH_UP = 3;

    private final Basilisk basilisk;
    private int nextStrikeAt;
    private int biteReadyAt;
    private int retreatBlockedUntil;
    private boolean clockwise;
    private double circleRadius;

    public BasiliskTactics(Basilisk basilisk) {
        this.basilisk = basilisk;
    }

    /** Arm on acquisition because the attack goal can run before the first stalk tick. */
    public void engage() {
        nextStrikeAt = Math.max(nextStrikeAt, basilisk.tickCount + BasiliskTuning.get().ticks(Dial.FIRST_LUNGE_DELAY));
        circleRadius = BasiliskTuning.get().between(Dial.CIRCLE_MIN, Dial.CIRCLE_MAX, basilisk.getRandom()) * reach();
        clockwise = basilisk.getRandom().nextBoolean();
    }

    public boolean strikeReady() {
        return basilisk.tickCount >= nextStrikeAt;
    }

    public boolean exhaustedSearch() {
        return basilisk.tickCount - nextStrikeAt >= BasiliskTuning.get().ticks(Dial.SEARCH_PATIENCE);
    }

    public boolean biteReady() {
        return basilisk.tickCount >= biteReadyAt;
    }

    /** After any strike, lunge or bite: wait the strike interval before the next and the bite cooldown before snapping again. */
    public void struck() {
        BasiliskTuning tuning = BasiliskTuning.get();
        nextStrikeAt = basilisk.tickCount + tuning.ticksBetween(Dial.STRIKE_INTERVAL_MIN, Dial.STRIKE_INTERVAL_MAX, basilisk.getRandom());
        biteReadyAt = basilisk.tickCount + tuning.ticks(Dial.BITE_COOLDOWN);
    }

    /** A retreat found nowhere to go: stop trying for a moment rather than starting one every tick. */
    public void retreatBlocked() {
        retreatBlockedUntil = basilisk.tickCount + 40;
    }

    public boolean canRetreat() {
        return basilisk.tickCount >= retreatBlockedUntil;
    }

    public void reverse() {
        clockwise = !clockwise;
    }

    /** Nearest and farthest it circles, for its size. */
    public double circleMin() {
        BasiliskTuning tuning = BasiliskTuning.get();
        return Math.min(tuning.get(Dial.CIRCLE_MIN), tuning.get(Dial.CIRCLE_MAX)) * reach();
    }

    public double circleMax() {
        BasiliskTuning tuning = BasiliskTuning.get();
        return Math.max(tuning.get(Dial.CIRCLE_MIN), tuning.get(Dial.CIRCLE_MAX)) * reach();
    }

    public double retreatDistance() {
        return BasiliskTuning.get().get(Dial.RETREAT_DISTANCE) * reach();
    }

    /** The next spot round the ring: a step ahead in the way it is going, the ring's radius kept, out of sight if it can be. */
    @Nullable
    public Vec3 circlePoint(LivingEntity target) {
        double bearing = bearing(target);
        double cover = BasiliskTuning.get().get(Dial.COVER);
        Vec3 best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        double sign = clockwise ? 1.0D : -1.0D;
        double min = circleMin();
        double max = circleMax();
        for (double step : CIRCLE_STEPS) {
            for (double offset : CIRCLE_RADIUS_OFFSETS) {
                double radius = Math.clamp(circleRadius + offset, min, max);
                Vec3 p = pointAround(target, bearing + sign * step * CIRCLE_ARC / radius, radius);
                if (TerrainProbe.isNearEdge(basilisk.level(), p.x, p.y, p.z)) continue;
                double score = -Math.abs(radius - circleRadius) * 0.3D - Math.abs(step - 1.0D)
                    + (cover > 0.0D && hiddenFrom(target, p) ? cover * COVER_SCORE : 0.0D) + basilisk.getRandom().nextDouble() * 0.5D;
                if (score > bestScore) {
                    bestScore = score;
                    best = p;
                }
            }
        }
        return best;
    }

    /** Where to retreat to: retreatDistance from the target, about straight away, out of sight if it can be. */
    @Nullable
    public Vec3 retreatPoint(LivingEntity target) {
        double bearing = bearing(target);
        double cover = BasiliskTuning.get().get(Dial.COVER);
        double radius = retreatDistance() + 1.5D; // aim a little past it, so it is passed while still moving
        Vec3 best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (double offset : RETREAT_BEARINGS) {
            Vec3 p = pointAround(target, bearing + offset, radius);
            if (TerrainProbe.isNearEdge(basilisk.level(), p.x, p.y, p.z)) continue;
            double score = -Math.abs(offset) * 1.5D + (hiddenFrom(target, p) ? cover * COVER_SCORE : 0.0D);
            if (score > bestScore) {
                bestScore = score;
                best = p;
            }
        }
        return best;
    }

    /** A spot at {@code radius} from the target, near the current bearing, from which the target can be seen to lunge at. */
    @Nullable
    public Vec3 strikePoint(LivingEntity target, double radius) {
        double bearing = bearing(target);
        for (double offset : STRIKE_BEARINGS) {
            Vec3 p = pointAround(target, bearing + offset, radius);
            if (!TerrainProbe.isNearEdge(basilisk.level(), p.x, p.y, p.z) && !hiddenFrom(target, p)) return p;
        }
        return null;
    }

    /** Radians, from the target to us. */
    public double bearing(LivingEntity target) {
        return Math.atan2(basilisk.getZ() - target.getZ(), basilisk.getX() - target.getX());
    }

    /** A spot round the target, on the ground there if there is ground near the lower of the two heights. */
    public Vec3 pointAround(LivingEntity target, double bearing, double radius) {
        double x = target.getX() + Math.cos(bearing) * radius;
        double z = target.getZ() + Math.sin(bearing) * radius;
        double y = Math.min(basilisk.getY(), target.getY());
        double ground = TerrainProbe.groundY(basilisk.level(), x, y + GROUND_SEARCH_UP, z, 2 * GROUND_SEARCH_UP);
        return new Vec3(x, Double.isNaN(ground) ? y : ground, z);
    }

    private double reach() {
        return BasiliskSize.reachMultiplier(basilisk.getScale());
    }

    /** Whether something solid stands between the target's eyes and where the head would be at {@code p}. */
    private boolean hiddenFrom(LivingEntity target, Vec3 p) {
        Level level = basilisk.level();
        Vec3 head = p.add(0.0D, basilisk.getBbHeight() * 0.5D, 0.0D);
        return level.clip(new ClipContext(target.getEyePosition(), head, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, target))
            .getType() != HitResult.Type.MISS;
    }
}
