package dev.syrval.verdantdeep.basilisk.entity;

import dev.syrval.verdantdeep.basilisk.entity.ai.TerrainProbe;

import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.phys.AABB;

/**
 * Whether a basilisk's whole body has room where it stands: its head box at its size, and every segment of the sleeping coil
 * on ground of its own. Natural spawns and a basilisk settling down to rest both start coiled, so this is the footprint that
 * keeps either from being embedded in terrain (a spawn inside a wall suffocates; a coil inside one looks broken).
 *
 * <p>Takes a {@link LevelReader} so chunk generation's spawn step can use it on its region.
 */
public final class BasiliskPlacement {
    /** Turns of the coil tried, a quarter apart: a coil that meets a wall one way round may fit another. */
    private static final int TURNS = 4;
    /** Shrinks each segment's box a little, so touching a neighbouring block's face is not a collision. */
    private static final double TOUCH = 0.05D;

    private BasiliskPlacement() {}

    /**
     * The yaw, starting from the basilisk's own and turning by quarters, at which its coil fits where it stands; NaN if none.
     * The head box is the entity's own bounding box, so the size must already be applied.
     */
    public static float coilYaw(LevelReader level, Basilisk basilisk) {
        if (!level.noCollision(basilisk, basilisk.getBoundingBox().deflate(TOUCH)) || containsLava(level, basilisk.getBoundingBox())) {
            return Float.NaN;
        }
        for (int turn = 0; turn < TURNS; turn++) {
            float yaw = basilisk.getYRot() + turn * 360.0F / TURNS;
            if (coilFits(level, basilisk, yaw)) return yaw;
        }
        return Float.NaN;
    }

    private static boolean coilFits(LevelReader level, Basilisk basilisk, float yaw) {
        double scale = basilisk.getBasiliskScale();
        int count = BasiliskSize.segmentCount(scale);
        double[][] layout = BasiliskSegmentChain.coilLayout(count, yaw, scale);
        double headY = basilisk.getY();
        // The coil settles each segment on ground within this height of the head (BasiliskSegmentChain.applyCoil).
        double reach = 1.5D * scale;
        int below = (int) Math.ceil(2.0D * reach);
        for (int i = 0; i < count; i++) {
            double x = basilisk.getX() + layout[i][0];
            double z = basilisk.getZ() + layout[i][1];
            double ground = TerrainProbe.groundY(level, x, headY + reach, z, below);
            if (Double.isNaN(ground) || Math.abs(ground - headY) >= reach) return false; // over a drop, or a wall or step too big
            double size = scale * BasiliskSegmentChain.taper(i, count);
            AABB box = new AABB(x - size / 2, ground, z - size / 2, x + size / 2, ground + size, z + size / 2).deflate(TOUCH);
            if (!level.noCollision(box) || containsLava(level, box)) return false;
        }
        return true;
    }

    private static boolean containsLava(LevelReader level, AABB box) {
        return level.getBlockStates(box).anyMatch(state -> state.getFluidState().is(FluidTags.LAVA));
    }
}
