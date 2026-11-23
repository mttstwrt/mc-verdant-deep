package dev.syrval.verdantdeep.basilisk.entity;

import dev.syrval.verdantdeep.basilisk.BasiliskEntities;
import dev.syrval.verdantdeep.basilisk.BasiliskTuning;
import dev.syrval.verdantdeep.basilisk.BasiliskTuning.Dial;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/** Recent crossings, held only while the animal is loaded. */
final class BasiliskTrail {
    private static final DustParticleOptions FRESH = new DustParticleOptions(new Vector3f(0.58F, 0.76F, 0.13F), 0.8F);
    private static final DustParticleOptions FADING = new DustParticleOptions(new Vector3f(0.32F, 0.43F, 0.12F), 0.5F);
    private final Vec3[] crossings = new Vec3[3];
    private final int[] markedAt = new int[crossings.length];
    private int nextMark;
    private double remainingDistance = 1;

    void tick(ServerLevel level, Basilisk basilisk) {
        if (!basilisk.isAsleep() && basilisk.onGround() && basilisk.getPhase() == BasiliskPhase.IDLE) {
            double dx = basilisk.getX() - basilisk.xo;
            double dz = basilisk.getZ() - basilisk.zo;
            remainingDistance -= Math.min(1, Math.sqrt(dx * dx + dz * dz));
            if (remainingDistance <= 0) {
                remainingDistance = BasiliskTuning.get().get(Dial.TRAIL_SPACING) * (0.75 + basilisk.getRandom().nextDouble() * 0.5);
                crossings[nextMark] = basilisk.position();
                markedAt[nextMark] = basilisk.tickCount;
                nextMark = (nextMark + 1) % crossings.length;
                basilisk.playSound(BasiliskEntities.SND_SLITHER.get(), 0.7F, 0.9F + basilisk.getRandom().nextFloat() * 0.3F);
            }
        }
        if ((basilisk.tickCount + basilisk.getId()) % 10 != 0) return;
        int lifetime = BasiliskTuning.get().ticks(Dial.TRAIL_LIFETIME);
        for (int i = 0; i < crossings.length; i++) {
            Vec3 p = crossings[i];
            if (p == null) continue;
            int age = basilisk.tickCount - markedAt[i];
            if (age >= lifetime) {
                crossings[i] = null;
                continue;
            }
            level.sendParticles(age < lifetime / 2 ? FRESH : FADING, p.x, p.y + 0.06, p.z, 2, 0.12, 0.01, 0.12, 0);
        }
    }
}
