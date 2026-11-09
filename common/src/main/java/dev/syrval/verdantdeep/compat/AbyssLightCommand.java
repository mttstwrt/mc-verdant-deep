package dev.syrval.verdantdeep.compat;

import com.mojang.brigadier.CommandDispatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * {@code /abyss light}: how dark the ground of the chasm the player is in is, theme by theme, in sun and in shade (see
 * {@code SunSettings}). Monsters spawn only at block light 0, so the share of shaded ground at 0 is what keeps the domes
 * dangerous. Only chunks that are loaded are measured, so what has not been generated or is far from any player is left out
 * and counted apart. Runs on the server thread.
 */
public final class AbyssLightCommand {
    // How far above the ground the open block a mob would stand in is looked for, past plants and low growth that block it.
    private static final int SEARCH = 4;

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
            tallies.computeIfAbsent(theme + (surface ? " (surface)" : ""), key -> new Tally()).add(sun > 0, light);
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

    // Columns with any of the sun count as sun, as growth marked sun may stand there.
    private static final class Tally {
        final Levels shade = new Levels();
        final Levels sun = new Levels();

        void add(boolean lit, int light) {
            (lit ? sun : shade).add(light);
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
