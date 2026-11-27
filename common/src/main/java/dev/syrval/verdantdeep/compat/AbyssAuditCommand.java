package dev.syrval.verdantdeep.compat;

import com.google.gson.stream.JsonWriter;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.syrval.verdantdeep.VerdantDeep;
import dev.syrval.verdantdeep.ravine.DiscBlockSink;
import dev.syrval.verdantdeep.ravine.DiscFallSink;
import dev.syrval.verdantdeep.ravine.DiscSpringSink;
import dev.syrval.verdantdeep.ravine.DiscSummary;
import dev.syrval.verdantdeep.ravine.RavineFootprint;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;

/**
 * {@code /abyss audit <x1> <z1> <x2> <z2>}: writes what generation planned for the discs of the chunks those columns touch to
 * {@code verdant_deep_audit/} beside the server's files, for comparing with a saved world. Per chunk: the blocks given a disc's
 * material, the ground over platforms, each column of disc water, each fall's still and falling water, and the places tried
 * for the hole's springs; with the discs of each hole met. It reads the footprint alone, so it needs no chunk loaded and
 * generates none, and it runs on a background thread, since a hole is a few hundred chunks of planning.
 */
public final class AbyssAuditCommand {
    // A hole is about 16 chunks across; this is room for two with their surroundings, and keeps a mistyped corner cheap.
    private static final int MOST_CHUNKS = 4096;

    private AbyssAuditCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // Operators only: a debugging aid that writes a file.
        dispatcher.register(Commands.literal("abyss").requires(source -> source.hasPermission(2))
                .then(Commands.literal("audit")
                        .then(Commands.argument("x1", IntegerArgumentType.integer())
                                .then(Commands.argument("z1", IntegerArgumentType.integer())
                                        .then(Commands.argument("x2", IntegerArgumentType.integer())
                                                .then(Commands.argument("z2", IntegerArgumentType.integer())
                                                        .executes(AbyssAuditCommand::run)))))));
    }

    private static int run(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        int x1 = IntegerArgumentType.getInteger(context, "x1");
        int z1 = IntegerArgumentType.getInteger(context, "z1");
        int x2 = IntegerArgumentType.getInteger(context, "x2");
        int z2 = IntegerArgumentType.getInteger(context, "z2");
        int minCx = Math.min(x1, x2) >> 4;
        int maxCx = Math.max(x1, x2) >> 4;
        int minCz = Math.min(z1, z2) >> 4;
        int maxCz = Math.max(z1, z2) >> 4;
        long chunks = (long) (maxCx - minCx + 1) * (maxCz - minCz + 1);
        if (chunks > MOST_CHUNKS) {
            source.sendFailure(Component.literal("At most " + MOST_CHUNKS + " chunks; that is " + chunks));
            return 0;
        }
        ServerLevel level = source.getLevel();
        MinecraftServer server = source.getServer();
        RavineFootprint footprint = RavineFootprintHolder.of(level);
        Path file = server.getServerDirectory().resolve("verdant_deep_audit")
                .resolve(String.format(Locale.ROOT, "plan_%d_%d_%d_%d.json", minCx, minCz, maxCx, maxCz));
        Area area = new Area(level.dimension().location().toString(), minCx, minCz, maxCx, maxCz, level.getMinBuildHeight(), level.getMaxBuildHeight());
        CompletableFuture.runAsync(() -> write(footprint, area, file), Util.backgroundExecutor()).whenComplete((done, failure) -> server.execute(() -> {
            if (failure != null) {
                VerdantDeep.LOGGER.error("The disc audit could not be written to {}", file, failure);
                source.sendFailure(Component.literal("The audit failed: " + failure.getMessage()));
            } else {
                VerdantDeep.LOGGER.info("Disc audit of {} chunks written to {}", chunks, file);
                source.sendSuccess(() -> Component.literal("Disc audit written to " + file), true);
            }
        }));
        return 1;
    }

    private record Area(String dimension, int minCx, int minCz, int maxCx, int maxCz, int minY, int maxY) {}

    private static void write(RavineFootprint footprint, Area area, Path file) {
        try {
            Files.createDirectories(file.getParent());
            try (Writer out = Files.newBufferedWriter(file); JsonWriter json = new JsonWriter(out)) {
                json.beginObject();
                json.name("version").value(1);
                json.name("dimension").value(area.dimension());
                json.name("min_y").value(area.minY());
                json.name("max_y").value(area.maxY());
                Map<String, DiscSummary> discs = new LinkedHashMap<>();
                json.name("chunks").beginArray();
                for (int cx = area.minCx(); cx <= area.maxCx(); cx++) {
                    for (int cz = area.minCz(); cz <= area.maxCz(); cz++) {
                        for (DiscSummary disc : footprint.discsAt(cx << 4, cz << 4)) {
                            discs.putIfAbsent(disc.holeX() + "," + disc.holeZ() + "#" + disc.index(), disc);
                        }
                        chunk(footprint, area, cx, cz, json);
                    }
                }
                json.endArray();
                json.name("discs").beginArray();
                for (DiscSummary disc : discs.values()) {
                    json.beginObject();
                    json.name("hole").beginArray().value(disc.holeX()).value(disc.holeZ()).endArray();
                    json.name("index").value(disc.index());
                    json.name("x").value(disc.x());
                    json.name("z").value(disc.z());
                    json.name("floor").value(disc.floor());
                    json.name("radius").value(disc.radius());
                    json.name("theme").value(disc.theme());
                    json.name("support").value(disc.support());
                    json.endObject();
                }
                json.endArray();
                json.endObject();
            }
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private static void chunk(RavineFootprint footprint, Area area, int cx, int cz, JsonWriter json) throws IOException {
        int minX = cx << 4;
        int minZ = cz << 4;
        int height = area.maxY() - area.minY();
        // Stems are painted before platforms and may pass through them, so a block can come twice: keep each once.
        BitSet rock = new BitSet(16 * 16 * height);
        BitSet ground = new BitSet(16 * 16 * height);
        DiscBlockSink rockSink = (x, y, z, block) -> rock.set(index(x - minX, y - area.minY(), z - minZ, height));
        DiscBlockSink groundSink = (x, y, z, block) -> ground.set(index(x - minX, y - area.minY(), z - minZ, height));
        footprint.forEachDiscBlock(minX, minZ, area.minY(), area.maxY(), rockSink);
        footprint.forEachMoundBlock(minX, minZ, area.minY(), area.maxY(), groundSink);
        if (rock.isEmpty() && ground.isEmpty() && !hasWater(footprint, minX, minZ)) {
            return;
        }
        json.beginObject();
        json.name("x").value(cx);
        json.name("z").value(cz);
        json.name("rock");
        runs(rock, minX, minZ, area.minY(), height, json);
        json.name("ground");
        runs(ground, minX, minZ, area.minY(), height, json);
        json.name("water").beginArray();
        IOException[] failed = new IOException[1];
        footprint.forEachDiscWater(minX, minZ, (x, surface, z, depth, fluid) -> tryWrite(failed, () -> json.beginArray()
                .value(x).value(surface).value(z).value(depth).value(fluid.getSerializedName()).endArray()));
        json.endArray();
        json.name("still").beginArray();
        List<int[]> flows = new ArrayList<>();
        footprint.forEachFallWater(minX, minZ, new DiscFallSink() {
            @Override
            public void accept(int x, int level, int z, int depth, int cut, boolean lip) {
                tryWrite(failed, () -> json.beginArray().value(x).value(level).value(z).value(depth).value(cut).value(lip).endArray());
            }

            @Override
            public void flow(int x, int z, int top, int bottom) {
                flows.add(new int[] {x, z, top, bottom});
            }
        });
        json.endArray();
        json.name("flow").beginArray();
        for (int[] flow : flows) {
            json.beginArray().value(flow[0]).value(flow[1]).value(flow[2]).value(flow[3]).endArray();
        }
        json.endArray();
        json.name("springs").beginArray();
        footprint.forEachSpring(minX, minZ, new DiscSpringSink() {
            @Override
            public void begin(ResourceKey<ConfiguredFeature<?, ?>> feature) {}

            @Override
            public void tryAt(int x, int y, int z) {
                tryWrite(failed, () -> json.beginArray().value(x).value(y).value(z).endArray());
            }
        });
        json.endArray();
        json.endObject();
        if (failed[0] != null) {
            throw failed[0];
        }
    }

    private static boolean hasWater(RavineFootprint footprint, int minX, int minZ) {
        boolean[] any = new boolean[1];
        footprint.forEachDiscWater(minX, minZ, (x, surface, z, depth, fluid) -> any[0] = true);
        footprint.forEachFallWater(minX, minZ, (x, level, z, depth, cut, lip) -> any[0] = true);
        return any[0];
    }

    private static int index(int x, int y, int z, int height) {
        return (x * 16 + z) * height + y;
    }

    // Each column's blocks as [x, z, bottom, top] runs, both ends inclusive.
    private static void runs(BitSet blocks, int minX, int minZ, int minY, int height, JsonWriter json) throws IOException {
        json.beginArray();
        for (int start = blocks.nextSetBit(0); start >= 0; ) {
            int column = start / height;
            int end = Math.min(blocks.nextClearBit(start), (column + 1) * height);
            json.beginArray().value(minX + column / 16).value(minZ + column % 16).value(minY + start % height).value(minY + (end - 1) % height).endArray();
            start = blocks.nextSetBit(end);
        }
        json.endArray();
    }

    private interface JsonStep {
        void run() throws IOException;
    }

    // The footprint's sinks cannot throw, so the first failure is kept and thrown once the chunk is done.
    private static void tryWrite(IOException[] failed, JsonStep step) {
        if (failed[0] != null) {
            return;
        }
        try {
            step.run();
        } catch (IOException e) {
            failed[0] = e;
        }
    }
}
