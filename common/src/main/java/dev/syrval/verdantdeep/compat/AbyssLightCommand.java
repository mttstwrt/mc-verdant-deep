package dev.syrval.verdantdeep.compat;

import com.mojang.brigadier.CommandDispatcher;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.stream.Collectors;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * {@code /abyss light}: how dark the ground of the chasm the player is in is, theme by theme, in sun and in shade (see
 * {@code SunSettings}). Monsters spawn only at block light 0, so the share of shaded ground at 0 is what keeps the domes
 * dangerous. Each lit shaded column is traced back up the light to the block it comes from, so the report says which
 * emitters to thin. Only chunks that are loaded are measured, so what has not been generated or is far from any player is
 * left out and counted apart. Runs on the server thread.
 */
public final class AbyssLightCommand {
    // How far above the ground the open block a mob would stand in is looked for, past plants and low growth that block it.
    private static final int SEARCH = 4;
    // How many emitters each theme's shade names, the ones lighting the most columns first.
    private static final int SOURCES_SHOWN = 10;

    private AbyssLightCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // Operators only, as /abyss stats.
        dispatcher.register(Commands.literal("abyss").requires(source -> source.hasPermission(2))
                .then(Commands.literal("light").executes(context -> run(context.getSource()))));
    }

    private static int run(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        BlockPos at = BlockPos.containing(source.getPosition());
        Map<String, Tally> tallies = new TreeMap<>();
        var counts = new int[3];
        var pos = new BlockPos.MutableBlockPos();
        boolean found = RavineFootprintHolder.of(level).forEachDiscColumn(at.getX(), at.getZ(), (theme, surface, sun, x, ground, z) -> {
            counts[0]++;
            LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
            if (chunk == null) {
                counts[1]++;
                return;
            }
            OptionalInt open = openAbove(chunk, pos, x, ground, z);
            if (open.isEmpty()) {
                counts[2]++;
                return;
            }
            int light = level.getBrightness(LightLayer.BLOCK, pos.set(x, open.getAsInt(), z));
            Tally tally = tallies.computeIfAbsent(theme + (surface ? " (surface)" : ""), key -> new Tally());
            tally.add(sun > 0, light);
            if (sun <= 0) {
                tally.nearestShade(at, x, open.getAsInt(), z);
            }
            if (sun <= 0 && light > 0) {
                tally.shadeSources.merge(sourceOf(level, pos, light), 1, Integer::sum);
            }
        });
        if (!found) {
            source.sendFailure(Component.literal("Not in a chasm"));
            return 0;
        }
        List<String> lines = new ArrayList<>();
        lines.add("Disc columns: " + counts[0] + ", in chunks not loaded " + counts[1] + ", under water or covered " + counts[2]);
        tallies.forEach((theme, tally) -> {
            lines.add(theme + ":");
            tally.shade.describe("shade").ifPresent(lines::add);
            tally.describeSources().ifPresent(lines::add);
            tally.describeNearest().ifPresent(lines::add);
            tally.sun.describe("sun").ifPresent(lines::add);
        });
        for (String line : lines) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    // The lowest open block over the ground of a column, or none where water stands on it or something taller than the
    // search covers it.
    private static OptionalInt openAbove(LevelChunk chunk, BlockPos.MutableBlockPos pos, int x, int ground, int z) {
        for (int y = ground + 1; y <= ground + SEARCH; y++) {
            BlockState state = chunk.getBlockState(pos.set(x, y, z));
            if (!state.getFluidState().isEmpty()) {
                return OptionalInt.empty();
            }
            if (!state.blocksMotion()) {
                return OptionalInt.of(y);
            }
        }
        return OptionalInt.empty();
    }

    // The block a block's light comes from: up the light, one step to a brighter neighbour at a time, to a block that gives at
    // least as much as it has. Light falls by one a block, so this takes at most 15 steps. "?" where the trail is lost, as
    // at the edge of what is loaded.
    private static String sourceOf(ServerLevel level, BlockPos.MutableBlockPos pos, int light) {
        var next = new BlockPos.MutableBlockPos();
        for (int step = 0; step <= 15; step++) {
            BlockState state = level.getBlockState(pos);
            if (state.getLightEmission() >= light) {
                return nameOf(state) + hungFrom(level, pos, state);
            }
            boolean climbed = false;
            for (Direction side : Direction.values()) {
                int there = level.getBrightness(LightLayer.BLOCK, next.setWithOffset(pos, side));
                if (there > light) {
                    pos.set(next);
                    light = there;
                    climbed = true;
                    break;
                }
            }
            if (!climbed) {
                break;
            }
        }
        return "?";
    }

    // What a hanging emitter hangs from, which tells the growth that put it there apart: berries under leaves are a canopy's,
    // under a disc's rock its underside's; lichen under a mushroom's cap is its gills.
    private static String hungFrom(ServerLevel level, BlockPos.MutableBlockPos pos, BlockState emitter) {
        boolean vine = emitter.is(Blocks.CAVE_VINES) || emitter.is(Blocks.CAVE_VINES_PLANT);
        if (!vine && !emitter.is(Blocks.GLOW_LICHEN)) {
            return "";
        }
        var above = new BlockPos.MutableBlockPos().set(pos);
        BlockState there;
        int y = 0;
        do {
            there = level.getBlockState(above.move(Direction.UP));
        } while (vine && (there.is(Blocks.CAVE_VINES) || there.is(Blocks.CAVE_VINES_PLANT)) && ++y < 32);
        return there.isAir() ? " (not under anything)" : " under " + nameOf(there);
    }

    private static String nameOf(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
    }

    // Columns with any of the sun count as sun, as growth marked sun may stand there.
    private static final class Tally {
        final Levels shade = new Levels();
        final Levels sun = new Levels();
        final Map<String, Integer> shadeSources = new HashMap<>();
        // The shaded open block over the ground nearest where the command was run from, to go and look at.
        private final BlockPos.MutableBlockPos nearest = new BlockPos.MutableBlockPos();
        private long nearestDistance = Long.MAX_VALUE;

        void nearestShade(BlockPos from, int x, int y, int z) {
            long dx = x - from.getX();
            long dz = z - from.getZ();
            if (dx * dx + dz * dz < nearestDistance) {
                nearestDistance = dx * dx + dz * dz;
                nearest.set(x, y, z);
            }
        }

        Optional<String> describeNearest() {
            return nearestDistance == Long.MAX_VALUE ? Optional.empty()
                    : Optional.of("  nearest shade: " + nearest.getX() + " " + nearest.getY() + " " + nearest.getZ());
        }

        void add(boolean lit, int light) {
            (lit ? sun : shade).add(light);
        }

        // Each emitter's share of the shade's columns: what turning it off would bring to block light 0, give or take the
        // columns another emitter lights as well.
        Optional<String> describeSources() {
            if (shadeSources.isEmpty()) {
                return Optional.empty();
            }
            String named = shadeSources.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(SOURCES_SHOWN)
                    .map(each -> each.getKey() + " " + Math.round(100.0 * each.getValue() / shade.columns) + "%")
                    .collect(Collectors.joining(", "));
            return Optional.of("  shade lit by: " + named);
        }
    }

    private static final class Levels {
        final int[] byLight = new int[16];
        int columns;

        void add(int light) {
            byLight[light]++;
            columns++;
        }

        Optional<String> describe(String name) {
            if (columns == 0) {
                return Optional.empty();
            }
            return Optional.of("  " + name + ": " + columns + " columns, " + percent(0, 0) + " at block light 0; 1-3 "
                    + percent(1, 3) + ", 4-7 " + percent(4, 7) + ", 8+ " + percent(8, 15));
        }

        private String percent(int from, int to) {
            int sum = 0;
            for (int light = from; light <= to; light++) {
                sum += byLight[light];
            }
            return Math.round(100.0 * sum / columns) + "%";
        }
    }
}
