package dev.syrval.verdantdeep.basilisk.entity.ai;

import java.util.EnumSet;

import dev.syrval.verdantdeep.basilisk.BasiliskEntities;
import dev.syrval.verdantdeep.basilisk.BasiliskTuning;
import dev.syrval.verdantdeep.basilisk.BasiliskTuning.Dial;
import dev.syrval.verdantdeep.basilisk.entity.Basilisk;
import dev.syrval.verdantdeep.basilisk.entity.BasiliskPhase;
import dev.syrval.verdantdeep.basilisk.entity.BasiliskSize;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

/**
 * The basilisk's strikes and the retreats after them: a fast lunge-and-bite predator that hits once and gets out, rather
 * than a heavy hitter that stays in melee. When strikes may come is BasiliskTactics' clock (the STRIKE_INTERVAL dials);
 * between them BasiliskStalkGoal circles.
 *
 *   LUNGE   strike due, target LUNGE_MIN_RANGE..LUNGE_MAX_RANGE away on a clear straight line: telegraph (REAR or COIL),
 *           a ~7 block dash (STRIKE) that hits once for 1.5x attack_damage plus knockback, then a RECOVERY
 *           (RECOVER) where it is slowed and exposed: the punish window.
 *   BITE    target within BITE_RANGE (it came too close) and the BITE_COOLDOWN is over: a very short head telegraph
 *           (COIL), then one snap for attack_damage with Poison as a side effect.
 *   RETREAT after either attack, or when the target comes too close and it cannot snap: run to RETREAT_DISTANCE from
 *           the target, out of its sight if it can, within RETREAT_TIME. Then the stalk goal circles again.
 *
 * Counterplay: the aim locks a few ticks before the dash, so changing direction makes it miss; a shield blocks
 * the hit and the poison; a lunge that would land in lava, an abyss or a wall is not started.
 *
 * NAGA: roughly NagaMovementPattern's INTIMIDATE -> CHARGE -> DAZE, with an aimed dash instead of a
 * path-following charge, so the pathfinder losing track cannot dodge it.
 */
public class BasiliskAttackGoal extends Goal {
    // --- bite
    private static final double BITE_RANGE = 3.0D;          // base blocks from the basilisk to the target (at scale 1)
    private static final double BITE_RANGE_PER_SCALE = 0.8D; // added per scale unit above 1: the hitbox is that much wider
    private static final double LUNGE_MIN_GAP = 1.0D;       // lunge starts this far beyond bite range
    private static final double BITE_HIT_LENIENCY = 0.8D;   // extra reach at the moment the bite lands
    private static final int BITE_WINDUP_TICKS = 5;
    private static final int BITE_HOLD_TICKS = 4;           // how long the open-jaw phase stays up after the bite
    // --- lunge
    private static final double LUNGE_MAX_RANGE = 9.0D;     // x BasiliskSize.reachMultiplier
    private static final double LUNGE_MAX_Y_DIFF = 1.5D;    // the dash is flat
    private static final int LUNGE_WINDUP_REAR_TICKS = 8;
    private static final int LUNGE_WINDUP_COIL_TICKS = 6;   // under a ceiling
    private static final int LUNGE_AIM_LOCK_TICKS = 3;
    private static final double LUNGE_SPEED = 1.0D;         // blocks per tick during the dash
    private static final double LUNGE_MAX_DISTANCE = 7.0D;  // blocks covered by a full dash, x BasiliskSize.reachMultiplier
    private static final float LUNGE_DAMAGE_MULTIPLIER = 1.5F;
    private static final double LUNGE_KNOCKBACK = 0.8D;
    private static final int LUNGE_RECOVER_TICKS = 20;
    private static final double LUNGE_RECOVER_SPEED_SCALE = 0.25D; // residual momentum kept each tick while recovering
    // --- retreat: distance, time and speed are the RETREAT dials
    private static final double TOO_CLOSE_SHARE = 0.6D;   // of the nearest ring: closer than this and it backs off
    private static final int REPATH_TICKS = 6;

    private enum Step { BITE_WINDUP, LUNGE_WINDUP, LUNGE, RECOVER, RETREAT, DONE }

    private final Basilisk basilisk;
    private Step step = Step.DONE;
    private int ticks;
    private int windup;
    private int lungeTicks;
    private int retreatTicks;
    private boolean hitLanded;
    private int windupTargetId;
    private Vec3 aim;
    private Vec3 lungeDir = Vec3.ZERO;

    public BasiliskAttackGoal(Basilisk basilisk) {
        this.basilisk = basilisk;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        LivingEntity t = basilisk.getTarget();
        if (t == null || !t.isAlive() || basilisk.isGroggy() || basilisk.getPhase() != BasiliskPhase.IDLE) return false;
        return canBite(t) || canLunge(t) || tooClose(t);
    }

    private boolean canBite(LivingEntity t) {
        return basilisk.distanceTo(t) <= biteRange() && basilisk.tactics().biteReady()
            && Math.abs(t.getY() - basilisk.getY()) <= 2.5D && basilisk.hasLineOfSight(t);
    }

    private boolean canLunge(LivingEntity t) {
        double d = basilisk.distanceTo(t);
        return basilisk.tactics().strikeReady() && d >= lungeMinRange() && d <= lungeMaxRange()
            && Math.abs(t.getY() - basilisk.getY()) <= LUNGE_MAX_Y_DIFF
            && basilisk.hasLineOfSight(t) && lungeLineIsClear(t);
    }

    /** Inside the ring and not striking: back off instead of staying in melee. */
    private boolean tooClose(LivingEntity t) {
        return basilisk.tactics().canRetreat() && basilisk.distanceTo(t) < Math.max(lungeMinRange(), TOO_CLOSE_SHARE * basilisk.tactics().circleMin())
            && basilisk.getSensing().hasLineOfSight(t);
    }

    @Override
    public boolean canContinueToUse() {
        return step != Step.DONE;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true; // our timers count ticks
    }

    @Override
    public void start() {
        LivingEntity t = basilisk.getTarget();
        basilisk.getNavigation().stop();
        ticks = 0;
        aim = null;
        hitLanded = false;
        if (t == null) {
            step = Step.DONE;
        } else if (canBite(t)) {
            step = Step.BITE_WINDUP;
            windup = BITE_WINDUP_TICKS;
            basilisk.setPhase(BasiliskPhase.COIL);
        } else if (!canLunge(t)) {
            beginRetreat();
        } else {
            boolean room = TerrainProbe.hasHeadroom(basilisk.level(), basilisk, Basilisk.HEAD_RAISE * basilisk.getScale() + 1.0D);
            windupTargetId = t.getId();
            step = Step.LUNGE_WINDUP;
            windup = room ? LUNGE_WINDUP_REAR_TICKS : LUNGE_WINDUP_COIL_TICKS;
            basilisk.setPhase(room ? BasiliskPhase.REAR : BasiliskPhase.COIL);
            basilisk.playSound(BasiliskEntities.SND_REAR.get(), 1.6F, 1.1F + basilisk.getRandom().nextFloat() * 0.2F);
        }
    }

    @Override
    public void stop() {
        step = Step.DONE;
        basilisk.setPhase(BasiliskPhase.IDLE);
        basilisk.getNavigation().stop();
    }

    @Override
    public void tick() {
        ticks++;
        LivingEntity t = basilisk.getTarget();
        if (t == null || !t.isAlive()) {
            if (step == Step.LUNGE) { beginRecover(); } else if (step != Step.RECOVER) { step = Step.DONE; }
        }
        switch (step) {
            case BITE_WINDUP -> tickBiteWindup(t);
            case LUNGE_WINDUP -> tickLungeWindup(t);
            case LUNGE -> tickLunge(t);
            case RECOVER -> tickRecover();
            case RETREAT -> tickRetreat(t);
            default -> { }
        }
    }

    // ---------------------------------------------------------------- bite

    private void tickBiteWindup(LivingEntity t) {
        basilisk.getNavigation().stop();
        face(t);
        if (ticks < windup) return;
        basilisk.setPhase(BasiliskPhase.STRIKE);
        basilisk.swing(InteractionHand.MAIN_HAND);
        basilisk.playSound(BasiliskEntities.SND_STRIKE.get(), 1.2F, 1.1F + basilisk.getRandom().nextFloat() * 0.2F);
        if (basilisk.distanceTo(t) <= biteRange() + BITE_HIT_LENIENCY && basilisk.hasLineOfSight(t)
            && basilisk.doHurtTarget(t)) { // a shield-blocked bite returns false, so blocking also blocks the poison
            basilisk.applyVenom(t);
        }
        basilisk.tactics().struck();
        beginRetreat();
    }

    // ---------------------------------------------------------------- lunge

    /** Straight, unobstructed and safe all the way to where a full-length dash would end. */
    private boolean lungeLineIsClear(LivingEntity t) {
        Vec3 dir = flatDirection(basilisk.position(), t.position());
        if (dir == null) return false;
        double reach = Math.min(basilisk.distanceTo(t) + 0.5D, lungeMaxDistance());
        int maxDrop = basilisk.getMaxFallDistance();
        for (double k = 1.0D; k <= reach + 0.5D; k += 1.0D) {
            double step = Math.min(k, reach);
            if (!basilisk.level().noCollision(basilisk, basilisk.getBoundingBox().move(dir.x * step, 0.0D, dir.z * step))) return false;
            if (TerrainProbe.isUnsafeLanding(basilisk.level(), basilisk.getX() + dir.x * step, basilisk.getY(),
                basilisk.getZ() + dir.z * step, maxDrop)) return false;
        }
        return true;
    }

    private void tickLungeWindup(LivingEntity t) {
        if (t.getId() != windupTargetId || (aim == null && !basilisk.getSensing().hasLineOfSight(t))) {
            step = Step.DONE;
            return;
        }
        basilisk.getNavigation().stop();
        if (ticks < windup - LUNGE_AIM_LOCK_TICKS) {
            face(t);
        } else if (aim == null) {
            // Lock on with a little lead. Changing direction after this makes it miss.
            aim = t.position().add(t.getDeltaMovement().multiply(1.0D, 0.0D, 1.0D).scale(2.0D));
        }
        if (ticks >= windup) beginLunge(t);
    }

    private void beginLunge(LivingEntity t) {
        Vec3 from = basilisk.position();
        Vec3 to = aim != null ? aim : t.position();
        Vec3 dir = flatDirection(from, to);
        if (dir == null) dir = Vec3.directionFromRotation(0.0F, basilisk.getYRot());
        double dist = Math.sqrt((to.x - from.x) * (to.x - from.x) + (to.z - from.z) * (to.z - from.z));
        lungeTicks = lungeTicksFor(dist, lungeMaxDistance());

        float yaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
        basilisk.setYRot(yaw);
        basilisk.yBodyRot = yaw;
        basilisk.yHeadRot = yaw;

        lungeDir = dir;
        hitLanded = false;
        step = Step.LUNGE;
        ticks = 0;
        basilisk.setPhase(BasiliskPhase.STRIKE);
        basilisk.setDeltaMovement(dir.x * LUNGE_SPEED, 0.1D, dir.z * LUNGE_SPEED);
        basilisk.hasImpulse = true;
        basilisk.playSound(BasiliskEntities.SND_STRIKE.get(), 1.4F, 0.95F + basilisk.getRandom().nextFloat() * 0.1F);
    }

    /** Ticks of dash needed to cover a flat distance (target distance plus a little overshoot), capped at one full dash. */
    static int lungeTicksFor(double flatDistance, double maxDistance) {
        double reach = Math.min(Math.max(flatDistance + 0.5D, 1.0D), maxDistance);
        return (int) Math.ceil(reach / LUNGE_SPEED);
    }

    private void tickLunge(LivingEntity t) {
        Vec3 mv = basilisk.getDeltaMovement();
        double speed = hitLanded ? LUNGE_SPEED * 0.2D : LUNGE_SPEED;
        basilisk.setDeltaMovement(lungeDir.x * speed, mv.y, lungeDir.z * speed);
        basilisk.hasImpulse = true;

        if (!hitLanded && t != null && t.isAlive()
            && basilisk.getBoundingBox().inflate(0.45D, 0.3D, 0.45D).intersects(t.getBoundingBox())) {
            basilisk.swing(InteractionHand.MAIN_HAND);
            basilisk.lungeHit(t, LUNGE_DAMAGE_MULTIPLIER, LUNGE_KNOCKBACK, lungeDir);
            hitLanded = true;
        }
        if (ticks >= lungeTicks || basilisk.horizontalCollision || (hitLanded && ticks >= 2)) beginRecover();
    }

    private void beginRecover() {
        step = Step.RECOVER;
        ticks = 0;
        basilisk.setPhase(BasiliskPhase.RECOVER);
        basilisk.getNavigation().stop();
        basilisk.tactics().struck();
    }

    private void tickRecover() {
        Vec3 mv = basilisk.getDeltaMovement();
        basilisk.setDeltaMovement(mv.x * LUNGE_RECOVER_SPEED_SCALE, mv.y, mv.z * LUNGE_RECOVER_SPEED_SCALE);
        if (ticks >= LUNGE_RECOVER_TICKS) beginRetreat();
    }

    // ---------------------------------------------------------------- retreat

    private void beginRetreat() {
        step = Step.RETREAT;
        ticks = 0;
        retreatTicks = BasiliskTuning.get().ticks(Dial.RETREAT_TIME);
        if (basilisk.getPhase() != BasiliskPhase.STRIKE) basilisk.setPhase(BasiliskPhase.IDLE);
    }

    /** Away from the target, out of its sight if it can, until RETREAT_DISTANCE is reached or RETREAT_TIME runs out. */
    private void tickRetreat(LivingEntity t) {
        if (ticks == BITE_HOLD_TICKS && basilisk.getPhase() == BasiliskPhase.STRIKE) basilisk.setPhase(BasiliskPhase.IDLE);
        BasiliskTactics tactics = basilisk.tactics();
        if (t == null || ticks >= retreatTicks) {
            step = Step.DONE;
            return;
        }
        if (!basilisk.getSensing().hasLineOfSight(t)) {
            if (basilisk.getNavigation().isDone()) step = Step.DONE;
            return;
        }
        if (basilisk.distanceTo(t) >= tactics.retreatDistance()) {
            step = Step.DONE;
            return;
        }
        basilisk.getLookControl().setLookAt(t, 40.0F, 40.0F);
        if (ticks % REPATH_TICKS != 1 || !basilisk.getNavigation().isDone()) return;
        Vec3 p = tactics.retreatPoint(t);
        if (p == null || !BasiliskNavigation.walkTo(basilisk, p, BasiliskTuning.get().get(Dial.RETREAT_SPEED))) {
            tactics.retreatBlocked(); // cornered: stand and face it; the stalk goal and the bite take it from here
            step = Step.DONE;
        }
    }

    // ---------------------------------------------------------------- helpers

    private double biteRange() {
        return BITE_RANGE + BITE_RANGE_PER_SCALE * (basilisk.getScale() - 1.0D);
    }

    private double lungeMinRange() {
        return biteRange() + LUNGE_MIN_GAP;
    }

    private double lungeMaxRange() {
        return LUNGE_MAX_RANGE * BasiliskSize.reachMultiplier(basilisk.getScale());
    }

    /** Where to stand to lunge from: the middle of the lunge range, for this basilisk's size. */
    static double strikeDistance(Basilisk basilisk) {
        double bite = BITE_RANGE + BITE_RANGE_PER_SCALE * (basilisk.getScale() - 1.0D);
        return 0.5D * (bite + LUNGE_MIN_GAP + LUNGE_MAX_RANGE * BasiliskSize.reachMultiplier(basilisk.getScale()));
    }

    private double lungeMaxDistance() {
        return LUNGE_MAX_DISTANCE * BasiliskSize.reachMultiplier(basilisk.getScale());
    }

    private void face(LivingEntity t) {
        basilisk.lookAt(t, 60.0F, 60.0F);
        basilisk.getLookControl().setLookAt(t, 60.0F, 60.0F);
    }

    /** Unit horizontal vector from a to b, or null if they are practically on top of each other. */
    private static Vec3 flatDirection(Vec3 a, Vec3 b) {
        double dx = b.x - a.x, dz = b.z - a.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        return len < 0.3D ? null : new Vec3(dx / len, 0.0D, dz / len);
    }
}
