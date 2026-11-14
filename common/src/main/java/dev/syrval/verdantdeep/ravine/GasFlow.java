package dev.syrval.verdantdeep.ravine;

import java.util.List;
import net.minecraft.resources.ResourceLocation;

/**
 * A region over one disc in which the gases its theme gives off drift toward its hole's axis: an upright cylinder standing on
 * the anchor (the middle of the bottom). It makes no gas itself; the hole's {@link GasSource}s do.
 *
 * @param media the gases it moves, each once
 */
public record GasFlow(List<ResourceLocation> media, double x, double y, double z, float radius, float height) {
    public GasFlow {
        media = List.copyOf(media);
    }
}
