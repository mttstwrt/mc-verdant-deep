package dev.syrval.verdantdeep.basilisk.entity.ai;

import java.util.ArrayDeque;
import java.util.EnumSet;

import dev.syrval.verdantdeep.basilisk.BasiliskTuning;
import dev.syrval.verdantdeep.basilisk.BasiliskTuning.Dial;
import dev.syrval.verdantdeep.basilisk.entity.Basilisk;
import dev.syrval.verdantdeep.basilisk.entity.BasiliskPhase;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Patrol of a territory round the den: purposeful walks of up to PATROL_LEG blocks to stops spread over PATROL_RADIUS, a
 * pause at each to look round, and stops picked away from the last few so it sweeps the territory rather than pacing one
 * corner. When it has been awake long enough (Basilisk.wantsRest) it heads back to the den and coils up there, or wherever on
 * the way the coil has room.
 *
 * Every stop is on walkable ground (vanilla LandRandomPos), with room for the head at its size, and not near a drop
 * (TerrainProbe.isNearEdge); the body follows the head's path.
 */
public class BasiliskPatrolGoal extends Goal {
    private static final int TRIES = 10;
    /** Shortest walk to a new stop, blocks: shorter ones read as fidgeting. */
    private static final double LEG_MIN = 6.0D;
    /** Stops within this of one of the last few count as revisits and are passed over if there is another choice. */
    private static final double REVISIT_DISTANCE = 6.0D;
    private static final int REMEMBERED_STOPS = 4;
    private static final int LEG_TIMEOUT_TICKS = 400;
    /** Close enough to the den to lie down. */
    private static final double DEN_ARRIVE_DISTANCE = 3.0D;
    /** On the way home it gives up and rests where the coil fits after this long. */
    private static final int HOMEWARD_TIMEOUT_TICKS = 600;
    private static final int VERTICAL_RANGE = 7;

    private final Basilisk basilisk;
    private final ArrayDeque<Vec3> recentStops = new ArrayDeque<>();
    // Timestamps in the basilisk's tickCount, not counters: the goal selector ticks this goal only every other tick.
    private int pauseUntil;
    private int legStartedAt;
    /** When the way home began; -1 while not heading home. */
    private int homewardSince = -1;
    /** The den cannot be reached or has no room: rest at the first stop that has. */
    private boolean denGivenUp;
    private boolean resting;

    public BasiliskPatrolGoal(Basilisk basilisk) {
        this.basilisk = basilisk;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (basilisk.isAsleep() || basilisk.getTarget() != null || basilisk.getPhase() != BasiliskPhase.IDLE || basilisk.isVehicle()) return false;
        resting = basilisk.wantsRest();
        if (resting) {
            if (homewardSince < 0) homewardSince = basilisk.tickCount;
            return !restHere();
        }
        homewardSince = -1;
        denGivenUp = false;
        return basilisk.tickCount >= pauseUntil;
    }

    @Override
    public boolean canContinueToUse() {
        return !basilisk.isAsleep() && basilisk.getTarget() == null && basilisk.getPhase() == BasiliskPhase.IDLE
            && basilisk.tickCount - legStartedAt < LEG_TIMEOUT_TICKS && !basilisk.getNavigation().isDone();
    }

    @Override
    public void start() {
        legStartedAt = basilisk.tickCount;
        double speed = BasiliskTuning.get().get(Dial.PATROL_SPEED);
        if (resting && !restAnywhere()) {
            Vec3 den = Vec3.atBottomCenterOf(basilisk.den());
            if (basilisk.getNavigation().moveTo(den.x, den.y, den.z, speed)) return;
            Vec3 toward = LandRandomPos.getPosTowards(basilisk, legRange(), VERTICAL_RANGE, den);
            if (toward != null && basilisk.getNavigation().moveTo(toward.x, toward.y, toward.z, speed)) return;
            denGivenUp = true; // no way home from here
        }
        Vec3 stop = pickStop();
        if (stop != null && basilisk.getNavigation().moveTo(stop.x, stop.y, stop.z, speed)) remember(stop);
    }

    @Override
    public void stop() {
        basilisk.getNavigation().stop();
        if (!resting) pauseUntil = basilisk.tickCount + BasiliskTuning.get().ticksBetween(Dial.PATROL_PAUSE_MIN, Dial.PATROL_PAUSE_MAX, basilisk.getRandom());
    }

    /**
     * Lie down if at the den, or anywhere once the way home has failed or taken too long, provided the coil has room. A den
     * without room now (something was built there) is given up on, so the next stop with room is used.
     */
    private boolean restHere() {
        boolean atDen = basilisk.den().closerToCenterThan(basilisk.position(), DEN_ARRIVE_DISTANCE);
        if ((atDen || restAnywhere()) && basilisk.settleToRest()) {
            homewardSince = -1;
            denGivenUp = false;
            return true;
        }
        if (atDen) denGivenUp = true;
        return false;
    }

    private boolean restAnywhere() {
        return denGivenUp || basilisk.tickCount - homewardSince >= HOMEWARD_TIMEOUT_TICKS;
    }

    /**
     * The best of a few walkable spots in reach, inside the territory (toward the den if it has strayed out), with room for the
     * head and away from drops; spots far from the last few stops are preferred, and among those the farther walks.
     */
    @Nullable
    private Vec3 pickStop() {
        BlockPos den = basilisk.den();
        double radius = BasiliskTuning.get().get(Dial.PATROL_RADIUS);
        boolean outside = !den.closerToCenterThan(basilisk.position(), radius);
        Vec3 best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < TRIES; i++) {
            Vec3 p = outside
                ? LandRandomPos.getPosTowards(basilisk, legRange(), VERTICAL_RANGE, Vec3.atBottomCenterOf(den))
                : LandRandomPos.getPos(basilisk, legRange(), VERTICAL_RANGE);
            if (p == null) continue;
            if (!outside && !den.closerToCenterThan(p, radius)) continue;
            double walk = p.distanceTo(basilisk.position());
            if (walk < LEG_MIN && !outside) continue;
            if (TerrainProbe.isNearEdge(basilisk.level(), p.x, p.y, p.z)) continue;
            if (!basilisk.level().noCollision(basilisk, basilisk.getBoundingBox().move(p.subtract(basilisk.position())).deflate(0.05D))) continue;
            double score = walk * 0.1D - (isRecent(p) ? 10.0D : 0.0D);
            if (score > bestScore) {
                bestScore = score;
                best = p;
            }
        }
        return best;
    }

    private int legRange() {
        return (int) Math.round(BasiliskTuning.get().get(Dial.PATROL_LEG));
    }

    private boolean isRecent(Vec3 p) {
        for (Vec3 stop : recentStops) {
            if (stop.distanceTo(p) < REVISIT_DISTANCE) return true;
        }
        return false;
    }

    private void remember(Vec3 stop) {
        if (recentStops.size() >= REMEMBERED_STOPS) recentStops.removeFirst();
        recentStops.addLast(stop);
    }
}
