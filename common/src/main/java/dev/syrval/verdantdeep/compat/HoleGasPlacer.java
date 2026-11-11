package dev.syrval.verdantdeep.compat;

import dev.syrval.verdantdeep.VerdantDeep;
import dev.syrval.verdantdeep.ravine.HoleGas;
import dev.syrval.verdantdeep.ravine.RavineFootprint;
import dev.syrval.volumetrics.api.VolumetricsApi;
import dev.syrval.volumetrics.api.server.ServerInputs;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.Util;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;

/**
 * Gives the holes their gas from the server, through the gas library: as each chunk loads, the inputs of the hole whose cell
 * holds it that are scoped to it (see {@link HoleGasInputs}). The library sends them to the players who track the chunk and
 * ends them when it unloads, so a chunk that loads again is given them again, and nothing here runs between chunk loads.
 *
 * <p>A hole's gas is worked out the first time a chunk of its cell loads, off the server thread, since that can mean building
 * its discs and reading terrain; meanwhile its chunks that load get nothing, and once it is known every chunk of the hole that
 * is loaded then is given its share. A cell with no hole is remembered as having none.
 *
 * <p>Server thread, apart from the working out. The loaders report chunk loads; levels are forgotten as they unload.
 */
public final class HoleGasPlacer {
    private final Map<ServerLevel, LevelHoles> levels = new IdentityHashMap<>();

    /** The holes of one level as far as they are known, by carve and then by cell. */
    private static final class LevelHoles {
        private final RavineFootprint footprint;
        private final ServerInputs inputs;
        private final List<Long2ObjectOpenHashMap<Hole>> byCell;

        LevelHoles(RavineFootprint footprint, ServerInputs inputs) {
            this.footprint = footprint;
            this.inputs = inputs;
            this.byCell = new ArrayList<>(footprint.carveCount());
            for (int carve = 0; carve < footprint.carveCount(); carve++) {
                byCell.add(new Long2ObjectOpenHashMap<>());
            }
        }
    }

    /** One cell's hole: its inputs, or null while they are being worked out. */
    private static final class Hole {
        private @Nullable HoleGasInputs inputs;
    }

    /** A chunk of a server level has loaded. Server thread. */
    public void chunkLoaded(ServerLevel level, ChunkPos chunk) {
        LevelHoles holes = levels.get(level);
        if (holes == null) {
            RavineFootprint footprint = RavineFootprintHolder.of(level);
            if (footprint.carveCount() == 0) {
                return;
            }
            holes = new LevelHoles(footprint, VolumetricsApi.owner(VerdantDeep.MOD_ID).server(level));
            levels.put(level, holes);
        }
        for (int carve = 0; carve < holes.byCell.size(); carve++) {
            long cell = holes.footprint.cellKeyAt(carve, chunk.getMiddleBlockX(), chunk.getMiddleBlockZ());
            Hole hole = holes.byCell.get(carve).get(cell);
            if (hole == null) {
                hole = new Hole();
                holes.byCell.get(carve).put(cell, hole);
                workOut(level, holes, carve, cell, hole);
            } else if (hole.inputs != null) {
                hole.inputs.placeIn(holes.inputs, chunk);
            }
        }
    }

    private void workOut(ServerLevel level, LevelHoles holes, int carve, long cell, Hole hole) {
        CompletableFuture.supplyAsync(() -> {
            long started = System.nanoTime();
            return holes.footprint.gasOfCell(carve, cell).map(found -> {
                HoleGasInputs made = HoleGasInputs.of(found);
                log(found, made, System.nanoTime() - started);
                return made;
            }).orElse(HoleGasInputs.NONE);
        }, Util.backgroundExecutor()).whenCompleteAsync((made, failure) -> {
            if (failure != null) {
                VerdantDeep.LOGGER.error("Could not work out the gas of a hole", failure);
                made = HoleGasInputs.NONE;
            }
            hole.inputs = made;
            if (levels.get(level) == holes && made != HoleGasInputs.NONE) {
                VerdantDeep.LOGGER.info("Placed {} gas inputs of that hole in the chunks of it already loaded", made.placeLoaded(holes.inputs, level));
            }
        }, level.getServer());
    }

    private static void log(RavineFootprint.HoleGasAt hole, HoleGasInputs made, long nanos) {
        VerdantDeep.LOGGER.info("The hole at ({}, {}) places {} gas sources, {} pulls and {} haze (worked out in {} ms)",
                (int) hole.x(), (int) hole.z(), made.sources, made.pulls, made.fogs, nanos / 1_000_000);
        if (made.dropped > 0) {
            VerdantDeep.LOGGER.warn("That hole has {} more gas sources than the {} placed; its themes spread gas too thickly",
                    made.dropped, HoleGas.MAX_SOURCES);
        }
    }

    /** A level unloaded: the library has ended its inputs with it. */
    public void levelUnloaded(ServerLevel level) {
        levels.remove(level);
    }

    public void clear() {
        levels.clear();
    }
}
