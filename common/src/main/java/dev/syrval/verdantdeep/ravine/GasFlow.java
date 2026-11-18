package dev.syrval.verdantdeep.ravine;

import java.util.List;
import net.minecraft.resources.ResourceLocation;

/**
 * A region over one disc in which its theme gas and simulated bank drift toward its hole's axis: an upright cylinder standing on
 * the anchor (the middle of the bottom). It makes no gas itself; the hole's sources and simulated fog banks do.
 *
 * @param media the gases it moves, each once
 */
public record GasFlow(List<ResourceLocation> media, double x, double y, double z, float radius, float height, float speed) {
    public GasFlow {
        media = List.copyOf(media);
    }
}
