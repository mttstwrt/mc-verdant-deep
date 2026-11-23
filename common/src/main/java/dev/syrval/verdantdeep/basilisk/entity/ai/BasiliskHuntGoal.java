package dev.syrval.verdantdeep.basilisk.entity.ai;

import dev.syrval.verdantdeep.basilisk.BasiliskTuning;
import dev.syrval.verdantdeep.basilisk.BasiliskTuning.Dial;
import dev.syrval.verdantdeep.basilisk.entity.Basilisk;
import dev.syrval.verdantdeep.basilisk.entity.BasiliskPhase;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.player.Player;

/** Unprovoked hunts stay within the den's territory. Retaliation has its own higher-priority goal. */
public final class BasiliskHuntGoal extends NearestAttackableTargetGoal<Player> {
    private final Basilisk basilisk;
    private int restUntil;

    public BasiliskHuntGoal(Basilisk basilisk) {
        super(basilisk, Player.class, true, player -> inTerritory(basilisk, player));
        this.basilisk = basilisk;
    }

    private static boolean inTerritory(Basilisk basilisk, LivingEntity target) {
        return basilisk.den().closerToCenterThan(target.position(), BasiliskTuning.get().get(Dial.PATROL_RADIUS));
    }

    @Override
    public boolean canUse() {
        return !basilisk.isAsleep() && basilisk.tickCount >= restUntil && super.canUse();
    }

    @Override
    public boolean canContinueToUse() {
        LivingEntity prey = basilisk.getTarget();
        if (prey == null) return false;
        if (basilisk.getPhase() == BasiliskPhase.IDLE && (basilisk.tactics().exhaustedSearch()
            || (basilisk.getSensing().hasLineOfSight(prey) && !inTerritory(basilisk, prey)))) return false;
        return super.canContinueToUse();
    }

    @Override
    public void stop() {
        super.stop();
        restUntil = basilisk.tickCount + BasiliskTuning.get().ticks(Dial.HUNT_REST);
    }
}
