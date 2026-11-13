package dev.syrval.verdantdeep.compat;

import dev.syrval.verdantdeep.VerdantDeep;
import dev.syrval.verdantdeep.ravine.HoleGas;
import dev.syrval.verdantdeep.ravine.RavineFootprint;
import dev.syrval.volumetrics.api.extend.ChunkPlacements;
import dev.syrval.volumetrics.api.extend.ChunkView;
import dev.syrval.volumetrics.api.extend.PlacementRule;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.Util;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Gives the holes their gas from the server, as the gas library's placement rule: for each chunk of a server level that
 * loads, the library asks for the inputs of the hole whose cell holds it that belong to that chunk (see
 * {@link HoleGasInputs}), adds them scoped to the chunk, sends them to the players who track it, ends them when it unloads
 * and asks again when it loads again. So this mod has no chunk event of its own, ticks nothing and holds no handle.
 *
 * <p>A hole's gas is worked out the first time a chunk of its cell loads, off the server thread, since that can mean building
 * its discs and reading terrain (about 170 ms for a large hole). Its chunks that load meanwhile hand the library that work
 * to wait for ({@link ChunkPlacements#later}), and each gets its share once it is done, if it is still loaded. A cell with no
 * hole is remembered as having none, so after the first chunk of a cell every chunk costs a hash probe per carve here.
 */
public final class HoleGasRule implements PlacementRule {
    /** What a chunk that waited for its hole does once the hole is worked out. */
    private static final Then<HoleGasInputs> PLACE = (inputs, chunk, out) -> inputs.placeIn(out, chunk.pos());

    @Override
    public Side side() {
        return Side.SERVER;
    }

    /** Levels whose terrain has no ravine carve (the Nether, the End, a world without the mod's terrain) get nothing. */
    @Override
    public @Nullable Placer start(Level level) {
        if (!(level instanceof ServerLevel server)) {
            return null;
        }
        RavineFootprint footprint = RavineFootprintHolder.of(server);
        return footprint.carveCount() == 0 ? null : new LevelHoles(footprint);
    }

    /** The holes of one level as far as they are known, by carve and then by cell. The server thread only. */
    private static final class LevelHoles implements Placer {
        private final RavineFootprint footprint;
        private final List<Long2ObjectOpenHashMap<CompletableFuture<HoleGasInputs>>> byCell;

        LevelHoles(RavineFootprint footprint) {
            this.footprint = footprint;
            this.byCell = new ArrayList<>(footprint.carveCount());
            for (int carve = 0; carve < footprint.carveCount(); carve++) {
                byCell.add(new Long2ObjectOpenHashMap<>());
            }
        }

        @Override
        public void place(ChunkView chunk, ChunkPlacements out) {
            ChunkPos pos = chunk.pos();
            for (int carve = 0; carve < byCell.size(); carve++) {
                long cell = footprint.cellKeyAt(carve, pos.getMiddleBlockX(), pos.getMiddleBlockZ());
                CompletableFuture<HoleGasInputs> hole = byCell.get(carve).get(cell);
                if (hole == null) {
                    hole = workOut(carve, cell);
                    byCell.get(carve).put(cell, hole);
                }
                HoleGasInputs ready = hole.getNow(null);
                if (ready == null) {
                    out.later(hole, PLACE);
                } else {
                    ready.placeIn(out, pos);
                }
            }
        }

        private CompletableFuture<HoleGasInputs> workOut(int carve, long cell) {
            return CompletableFuture.supplyAsync(() -> {
                long started = System.nanoTime();
                return footprint.gasOfCell(carve, cell).map(found -> {
                    HoleGasInputs made = HoleGasInputs.of(found);
                    log(found, made, System.nanoTime() - started);
                    return made;
                }).orElse(HoleGasInputs.NONE);
            }, Util.backgroundExecutor()).exceptionally(failure -> {
                VerdantDeep.LOGGER.error("Could not work out the gas of a hole", failure);
                return HoleGasInputs.NONE;
            });
        }
    }

    private static void log(RavineFootprint.HoleGasAt hole, HoleGasInputs made, long nanos) {
        VerdantDeep.LOGGER.info("The hole at ({}, {}) places {} gas sources, {} pulls and {} haze in {} chunks (worked out in {} ms)",
                (int) hole.x(), (int) hole.z(), made.sources, made.pulls, made.fogs, made.chunks(), nanos / 1_000_000);
        if (made.dropped > 0) {
            VerdantDeep.LOGGER.warn("That hole has {} more gas sources than the {} placed; its themes spread gas too thickly",
                    made.dropped, HoleGas.MAX_SOURCES);
        }
    }
}
