package dev.syrval.verdantdeep.basilisk.entity.ai;

import java.util.EnumSet;

import dev.syrval.verdantdeep.basilisk.BasiliskTuning;
import dev.syrval.verdantdeep.basilisk.BasiliskTuning.Dial;
import dev.syrval.verdantdeep.basilisk.entity.Basilisk;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

/**
 * Between strikes: circle and shadow the target at a distance, out of its sight where there is cover, and keep the ring
 * centred on it as it moves. When the next strike is due (BasiliskTactics), close in to lunge range on a bearing with a clear
 * view; BasiliskAttackGoal, which preempts this goal, takes over as soon as a lunge can start. A target that runs beyond the
 * ring is chased.
 *
 * NAGA: NagaMovementPattern's CIRCLE state does the circling with findCirclePoint(); the point-on-a-ring idea is the same.
 * Its CHARGE/INTIMIDATE/DAZE/CRUMBLE states live in the attack goal here.
 */
public class BasiliskStalkGoal extends Goal {
    private static final int REPATH_MIN = 8;              // ticks between repaths
    private static final int REPATH_RANGE = 7;
    private static final double CHASE_MARGIN = 4.0D;      // blocks beyond the widest ring before it gives chase
    private static final double REVERSE_CHANCE = 0.12D;   // per repath: turn round the other way
    private static final double NEW_RING_CHANCE = 0.08D;  // per repath: drift to another ring
    private static final double CHASE_SPEED = 1.1D;

    private final Basilisk basilisk;
    private int repathIn;

    public BasiliskStalkGoal(Basilisk basilisk) {
        this.basilisk = basilisk;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        LivingEntity target = basilisk.getTarget();
        return target != null && target.isAlive();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        basilisk.tactics().engage();
        repathIn = 0;
    }

    @Override
    public void stop() {
        basilisk.getNavigation().stop();
    }

    @Override
    public void tick() {
        LivingEntity target = basilisk.getTarget();
        if (target == null) return;
        basilisk.getLookControl().setLookAt(target, 30.0F, 30.0F);

        if (--repathIn > 0) return;
        repathIn = REPATH_MIN + basilisk.getRandom().nextInt(REPATH_RANGE);
        BasiliskTactics tactics = basilisk.tactics();
        double speed = BasiliskTuning.get().get(Dial.CIRCLE_SPEED);

        double distance = basilisk.distanceTo(target);
        if (distance > tactics.circleMax() + CHASE_MARGIN) {
            basilisk.getNavigation().moveTo(target, CHASE_SPEED);
            return;
        }
        if (tactics.strikeReady()) {
            // Due to strike but the attack goal has not started: out of lunge range or no clear line. Line one up.
            Vec3 p = tactics.strikePoint(target, BasiliskAttackGoal.strikeDistance(basilisk));
            if (p == null || !basilisk.getNavigation().moveTo(p.x, p.y, p.z, speed)) basilisk.getNavigation().moveTo(target, speed);
            return;
        }
        if (basilisk.getRandom().nextDouble() < REVERSE_CHANCE) tactics.reverse();
        if (basilisk.getRandom().nextDouble() < NEW_RING_CHANCE) tactics.newCircle();
        Vec3 p = tactics.circlePoint(target);
        if (p != null && basilisk.getNavigation().moveTo(p.x, p.y, p.z, speed)) return;
        // Hemmed in (a ledge, a thicket): hold here and watch, rather than closing to melee. The next strike still comes.
        tactics.reverse();
        basilisk.getNavigation().stop();
    }
}
