package dev.syrval.verdantdeep.basilisk.entity.ai;

import java.util.EnumSet;

import dev.syrval.verdantdeep.basilisk.BasiliskTuning;
import dev.syrval.verdantdeep.basilisk.BasiliskTuning.Dial;
import dev.syrval.verdantdeep.basilisk.entity.Basilisk;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Shadow a visible target on one flank, or investigate its last crossing once sight is lost. */
public class BasiliskStalkGoal extends Goal {
    private static final int REPATH_MIN = 8;
    private static final int REPATH_RANGE = 7;
    private static final double LEG_TARGET_DRIFT_SQR = 4.0D * 4.0D;

    private final Basilisk basilisk;
    private LivingEntity observedTarget;
    private Vec3 lastSeen;
    private Vec3 legCenter;
    private boolean searchedCrossing;
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
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void stop() {
        basilisk.getNavigation().stop();
        observedTarget = null;
        lastSeen = null;
        legCenter = null;
    }

    @Override
    public void tick() {
        LivingEntity target = basilisk.getTarget();
        if (target == null) return;
        if (observedTarget != target) {
            basilisk.getNavigation().stop();
            observedTarget = target;
            lastSeen = null;
            legCenter = null;
            searchedCrossing = false;
            repathIn = 0;
        }
        double speed = BasiliskTuning.get().get(Dial.CIRCLE_SPEED);
        if (!basilisk.getSensing().hasLineOfSight(target)) {
            if (lastSeen != null) {
                basilisk.getLookControl().setLookAt(lastSeen.x, lastSeen.y + basilisk.getEyeHeight(), lastSeen.z);
                if (!searchedCrossing && basilisk.getNavigation().isDone()) {
                    searchedCrossing = true;
                    if (!TerrainProbe.isNearEdge(basilisk.level(), lastSeen.x, lastSeen.y, lastSeen.z)) moveTo(lastSeen, speed);
                }
            }
            return;
        }

        basilisk.getLookControl().setLookAt(target, 30.0F, 30.0F);
        lastSeen = target.position();
        if (searchedCrossing) {
            basilisk.getNavigation().stop();
            searchedCrossing = false;
            legCenter = null;
            repathIn = 0;
        }
        if (--repathIn > 0) return;
        repathIn = REPATH_MIN + basilisk.getRandom().nextInt(REPATH_RANGE);
        if (!basilisk.getNavigation().isDone() && legCenter != null && legCenter.distanceToSqr(lastSeen) < LEG_TARGET_DRIFT_SQR) return;

        BasiliskTactics tactics = basilisk.tactics();
        legCenter = lastSeen;
        if (tactics.strikeReady() && moveTo(tactics.strikePoint(target, BasiliskAttackGoal.strikeDistance(basilisk)), speed)) return;
        if (moveTo(tactics.circlePoint(target), speed)) return;
        tactics.reverse();
        basilisk.getNavigation().stop();
    }

    private boolean moveTo(@Nullable Vec3 point, double speed) {
        if (point == null || basilisk.position().distanceToSqr(point) < 1.0D) return false;
        return BasiliskNavigation.walkTo(basilisk, point, speed);
    }
}
