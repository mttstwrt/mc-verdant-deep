package dev.syrval.verdantdeep.basilisk.entity.ai;

import dev.syrval.verdantdeep.basilisk.entity.Basilisk;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Jungle routes need a larger node budget. Use ordinary ground navigation so rejecting a candidate or stopping a leg
 * also stops steering toward it. Spider navigation keeps a second destination even after the active path is cleared.
 */
public class BasiliskNavigation extends GroundPathNavigation {
    public BasiliskNavigation(Mob mob, Level level) {
        super(mob, level);
    }

    static boolean walkTo(Basilisk basilisk, Vec3 point, double speed) {
        Path path = basilisk.getNavigation().createPath(BlockPos.containing(point), 0);
        if (path == null || !path.canReach() || path.getNodeCount() < 2) return false;

        Vec3 previous = basilisk.position();
        for (int i = 1; i < path.getNodeCount(); i++) {
            Vec3 center = path.getEntityPosAtNode(basilisk, i);
            if (TerrainProbe.isNearEdge(basilisk.level(), center.x, center.y, center.z)) return false;
            if (i + 1 < path.getNodeCount()) {
                Vec3 next = path.getEntityPosAtNode(basilisk, i + 1);
                double inX = center.x - previous.x, inZ = center.z - previous.z;
                double outX = next.x - center.x, outZ = next.z - center.z;
                if (inX * outZ != inZ * outX && !bendFits(basilisk, previous, next)) return false;
            }
            previous = center;
        }
        return basilisk.getNavigation().moveTo(path, speed);
    }

    /** The trailing segments cut across the inside of a turn between adjacent navigation nodes. */
    private static boolean bendFits(Basilisk basilisk, Vec3 before, Vec3 after) {
        Vec3 inside = before.lerp(after, 0.5D);
        double size = basilisk.getBasiliskScale();
        double ground = TerrainProbe.groundY(basilisk.level(), inside.x, inside.y + 1, inside.z, TerrainProbe.EDGE_DROP);
        if (Double.isNaN(ground) || ground > Math.max(before.y, after.y) + 0.5D
            || TerrainProbe.isNearEdge(basilisk.level(), inside.x, ground, inside.z)) return false;
        double half = size * 0.5D;
        AABB segment = new AABB(inside.x - half, ground, inside.z - half, inside.x + half, ground + size, inside.z + half);
        return !basilisk.level().getBlockCollisions(basilisk, segment.deflate(0.05D)).iterator().hasNext();
    }

    @Override
    protected PathFinder createPathFinder(int maxVisitedNodes) {
        // Mirrors GroundPathNavigation.createPathFinder, with a larger budget.
        this.nodeEvaluator = new WalkNodeEvaluator();
        this.nodeEvaluator.setCanPassDoors(true);
        return new PathFinder(this.nodeEvaluator, maxVisitedNodes * 2);
    }
}
