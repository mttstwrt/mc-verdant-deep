package dev.syrval.verdantdeep.basilisk.entity.ai;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;

import dev.syrval.verdantdeep.basilisk.BasiliskTuning;
import dev.syrval.verdantdeep.basilisk.BasiliskTuning.Dial;
import dev.syrval.verdantdeep.basilisk.entity.Basilisk;
import dev.syrval.verdantdeep.basilisk.entity.BasiliskPhase;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.phys.Vec3;

/**
 * Patrol of a territory round the den: purposeful walks of up to PATROL_LEG blocks to stops spread over PATROL_RADIUS, a
 * pause at each to look round, and stops picked away from the last few so it sweeps the territory rather than pacing one
 * corner. When it has been awake long enough (Basilisk.wantsRest) it heads back to the den and coils up there, or wherever on
 * the way the coil has room.
 *
 * Rank cheap endpoint samples first. Check the actual ground route and the space inside its bends only for finalists.
 */
public class BasiliskPatrolGoal extends Goal {
    private record Stop(Vec3 point, double score) {}

    private static final int TRIES = 10;
    private static final int ROUTE_ATTEMPTS = 2;
    private static final int FAILED_LEG_PAUSE = 20;
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
            return !restHere() && basilisk.tickCount >= pauseUntil;
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
            if (BasiliskNavigation.walkTo(basilisk, den, speed)) return;
            Vec3 toward = LandRandomPos.getPosTowards(basilisk, legRange(), VERTICAL_RANGE, den);
            if (toward != null && BasiliskNavigation.walkTo(basilisk, toward, speed)) return;
            denGivenUp = true; // no way home from here
        }
        if (!startPatrolLeg(speed)) pauseUntil = basilisk.tickCount + FAILED_LEG_PAUSE;
    }

    @Override
    public void stop() {
        basilisk.getNavigation().stop();
        if (!resting) {
            int pause = BasiliskTuning.get().ticksBetween(Dial.PATROL_PAUSE_MIN, Dial.PATROL_PAUSE_MAX, basilisk.getRandom());
            pauseUntil = Math.max(pauseUntil, basilisk.tickCount + pause);
        }
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
    private boolean startPatrolLeg(double speed) {
        BlockPos den = basilisk.den();
        double radius = BasiliskTuning.get().get(Dial.PATROL_RADIUS);
        boolean outside = !den.closerToCenterThan(basilisk.position(), radius);
        var candidates = new ArrayList<Stop>(TRIES);
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
            candidates.add(new Stop(p, score));
        }
        candidates.sort(Comparator.comparingDouble(Stop::score).reversed());
        int attempts = 0;
        Vec3 first = null;
        for (Stop stop : candidates) {
            Vec3 point = stop.point();
            if (first != null && point.distanceToSqr(first) < REVISIT_DISTANCE * REVISIT_DISTANCE) continue;
            if (BasiliskNavigation.walkTo(basilisk, point, speed)) {
                remember(point);
                return true;
            }
            if (++attempts == ROUTE_ATTEMPTS) break;
            first = point;
        }
        return false;
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
