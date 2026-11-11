package dev.syrval.verdantdeep.compat;

import dev.syrval.verdantdeep.VerdantDeep;
import dev.syrval.verdantdeep.ravine.GasFlow;
import dev.syrval.verdantdeep.ravine.GasFog;
import dev.syrval.verdantdeep.ravine.GasShape;
import dev.syrval.verdantdeep.ravine.GasSource;
import dev.syrval.verdantdeep.ravine.HoleGas;
import dev.syrval.verdantdeep.ravine.RavineFootprint;
import dev.syrval.volumetrics.api.Scope;
import dev.syrval.volumetrics.api.VolumetricsRegistries;
import dev.syrval.volumetrics.api.field.Altitude;
import dev.syrval.volumetrics.api.field.Constant;
import dev.syrval.volumetrics.api.field.Cylinder;
import dev.syrval.volumetrics.api.field.Exponential;
import dev.syrval.volumetrics.api.field.Field;
import dev.syrval.volumetrics.api.field.Inside;
import dev.syrval.volumetrics.api.field.Linear;
import dev.syrval.volumetrics.api.field.Mul;
import dev.syrval.volumetrics.api.field.PlacedField;
import dev.syrval.volumetrics.api.field.Sphere;
import dev.syrval.volumetrics.api.force.Force;
import dev.syrval.volumetrics.api.force.Toward;
import dev.syrval.volumetrics.api.medium.Medium;
import dev.syrval.volumetrics.api.placement.Attachment;
import dev.syrval.volumetrics.api.server.ServerInputs;
import dev.syrval.volumetrics.api.source.Source;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/**
 * One hole's gas (see {@link HoleGas}) as the gas library's inputs, sorted by the chunk each is scoped to: its sources, a pull
 * toward the hole's axis over each disc that gives off gas, and its haze. Built once per hole, off the server thread; after
 * that a chunk that loads costs one hash probe here and the library's own work for each input it holds, and nothing is made
 * per chunk. Nothing ticks: the library ends each input when its chunk unloads, and sends what it holds to the players who
 * track that chunk (the haze, a placed field, to everyone in the level).
 *
 * <ul>
 *   <li>A source is scoped to the chunk of its anchor. Sources of equal definition share one {@link Source} instance, so the
 *   library's payloads write each kind of source of a hole once however many there are.</li>
 *   <li>A pull is anchored on the hole's axis, since a {@code toward} force draws to the upright line through its anchor, with
 *   its region moved out over the disc; it is scoped to the chunk of the disc's middle, so it reaches the players near the
 *   disc.</li>
 *   <li>The haze is scoped to the chunk of the hole's middle, which any player in or over a hole has loaded at the usual view
 *   distances: the cone's mouth is 45 blocks round and its cavern 136.</li>
 * </ul>
 */
final class HoleGasInputs {
    static final HoleGasInputs NONE = new HoleGasInputs(new Long2ObjectOpenHashMap<>(), 0, 0, 0, 0);
    // How fast the gas over a disc drifts toward its hole's axis, in blocks per second, and how far past the disc's region
    // the pull still acts, in blocks: what the zero-rate emitters with a flow gave before v2.
    private static final float PULL_SPEED = 1.5F;
    private static final float PULL_REACH = 6F;
    private static final float PULL_TAPER = 4F;

    // By ChunkPos.asLong. A hash map: a hole's inputs touch a few hundred of the thousands of chunks round it, and each chunk
    // that loads asks once.
    private final Long2ObjectMap<ChunkInputs> byChunk;
    final int sources;
    final int pulls;
    final int fogs;
    final int dropped;
    // Server thread: whether a failed add has been logged, so a missing medium is named once for the hole.
    private boolean warned;

    private HoleGasInputs(Long2ObjectMap<ChunkInputs> byChunk, int sources, int pulls, int fogs, int dropped) {
        this.byChunk = byChunk;
        this.sources = sources;
        this.pulls = pulls;
        this.fogs = fogs;
        this.dropped = dropped;
    }

    /** Any thread. */
    static HoleGasInputs of(RavineFootprint.HoleGasAt hole) {
        HoleGas gas = hole.gas();
        Map<Long, Building> chunks = new HashMap<>();
        Map<Source, Source> shared = new HashMap<>();
        for (GasSource each : gas.sources()) {
            Source source = shared.computeIfAbsent(sourceOf(each), made -> made);
            chunks.computeIfAbsent(chunkOf(each.x(), each.z()), Building::new).add(source, Attachment.at(new Vec3(each.x(), each.y(), each.z())));
        }
        for (GasFlow flow : gas.flows()) {
            // Measured from the anchor on the axis, at the height of the region's foot.
            Cylinder region = new Cylinder(new Vec3(flow.x() - hole.x(), 0, flow.z() - hole.z()), flow.height(), flow.radius());
            List<ResourceKey<Medium>> media = flow.media().stream().map(HoleGasInputs::medium).toList();
            Force pull = new Force(new Toward(PULL_SPEED, region, PULL_REACH, PULL_TAPER), media);
            chunks.computeIfAbsent(chunkOf(flow.x(), flow.z()), Building::new).add(pull, Attachment.at(new Vec3(hole.x(), flow.y(), hole.z())));
        }
        int fogs = 0;
        for (GasFog fog : gas.fogs()) {
            if (fog.bottomDensity() > 0 || fog.topDensity() > 0) {
                // Written where it is in the world, so it goes at the world's origin.
                chunks.computeIfAbsent(chunkOf(hole.x(), hole.z()), Building::new).add(hazeOf(fog), Attachment.at(Vec3.ZERO));
                fogs++;
            }
        }
        Long2ObjectOpenHashMap<ChunkInputs> byChunk = new Long2ObjectOpenHashMap<>(chunks.size());
        chunks.forEach((key, building) -> byChunk.put((long) key, building.build()));
        return new HoleGasInputs(byChunk, gas.sources().size(), gas.flows().size(), fogs, gas.dropped());
    }

    /** Adds what is scoped to the chunk, if anything is. Server thread. */
    void placeIn(ServerInputs inputs, ChunkPos chunk) {
        ChunkInputs here = byChunk.get(chunk.toLong());
        if (here != null) {
            place(inputs, here);
        }
    }

    /**
     * Adds what is scoped to each chunk of the level that is loaded now: for a hole worked out after some of it loaded.
     *
     * @return how many inputs it added
     */
    int placeLoaded(ServerInputs inputs, ServerLevel level) {
        int added = 0;
        for (ChunkInputs here : byChunk.values()) {
            if (level.hasChunk(here.scope.chunk().x, here.scope.chunk().z)) {
                place(inputs, here);
                added += here.definitions.length;
            }
        }
        return added;
    }

    private void place(ServerInputs inputs, ChunkInputs here) {
        for (int i = 0; i < here.definitions.length; i++) {
            try {
                switch (here.definitions[i]) {
                    case Source source -> inputs.add(source, here.attachments[i], here.scope);
                    case Force force -> inputs.add(force, here.attachments[i], here.scope);
                    case PlacedField field -> inputs.add(field, here.attachments[i], here.scope);
                    default -> throw new IllegalStateException("Not an input: " + here.definitions[i]);
                }
            } catch (IllegalArgumentException e) {
                // A medium the server's data lacks, say: the rest of the hole still has its gas.
                if (!warned) {
                    warned = true;
                    VerdantDeep.LOGGER.warn("Some of a hole's gas could not be placed; it is left out: {}", e.getMessage());
                }
            }
        }
    }

    private static Source sourceOf(GasSource source) {
        Field shape = switch (source.shape()) {
            case GasShape.Cylinder cylinder -> new Cylinder(Vec3.ZERO, cylinder.height(), cylinder.radius());
            case GasShape.Sphere sphere -> new Sphere(Vec3.ZERO, sphere.radius());
        };
        Source made = Source.of(medium(source.medium()), shape, new Source.Rate(source.rate()));
        return source.rise() == 0 ? made : made.withVelocity(new Vec3(0, source.rise(), 0));
    }

    // inside(the round shape, thinning over its edge) times its profile up the height, which the library draws in one step: by
    // the same share each block from the bottom density to the top one, or evenly where either is 0.
    private static PlacedField hazeOf(GasFog fog) {
        float bottom = (float) fog.y();
        float top = bottom + fog.height();
        Field profile;
        if (fog.bottomDensity() == fog.topDensity()) {
            profile = new Constant(fog.bottomDensity());
        } else if (fog.bottomDensity() > 0 && fog.topDensity() > 0) {
            float scaleHeight = (float) (fog.height() / Math.log(fog.bottomDensity() / fog.topDensity()));
            profile = new Exponential(Altitude.INSTANCE, bottom, top, fog.bottomDensity(), scaleHeight);
        } else {
            profile = new Linear(Altitude.INSTANCE, bottom, top, fog.bottomDensity(), fog.topDensity());
        }
        Cylinder shape = new Cylinder(new Vec3(fog.x(), fog.y(), fog.z()), fog.height(), fog.bottomRadius(), fog.topRadius());
        return PlacedField.drawn(medium(fog.medium()), new Mul(List.of(new Inside(shape, fog.edge()), profile)));
    }

    private static ResourceKey<Medium> medium(ResourceLocation id) {
        return ResourceKey.create(VolumetricsRegistries.MEDIUM, id);
    }

    private static long chunkOf(double x, double z) {
        return ChunkPos.asLong((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
    }

    /** What is scoped to one chunk, in arrays: walked once each time the chunk loads. */
    private record ChunkInputs(Scope.OfChunk scope, Object[] definitions, Attachment[] attachments) {}

    private static final class Building {
        private final Scope.OfChunk scope;
        private final List<Object> definitions = new ArrayList<>();
        private final List<Attachment> attachments = new ArrayList<>();

        Building(long chunk) {
            scope = new Scope.OfChunk(new ChunkPos(chunk));
        }

        void add(Object definition, Attachment attachment) {
            definitions.add(definition);
            attachments.add(attachment);
        }

        ChunkInputs build() {
            return new ChunkInputs(scope, definitions.toArray(), attachments.toArray(Attachment[]::new));
        }
    }
}
