package dev.syrval.verdantdeep.basilisk;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.architectury.platform.Platform;
import dev.syrval.verdantdeep.basilisk.BasiliskTuning.Dial;
import java.util.Arrays;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

/**
 * {@code /abyss basilisk}: the basilisk's tuning, live. With no more it lists every value; with a name it shows that one, and
 * with a value as well sets it at once for every basilisk. {@code save} writes the values to the config file, {@code reload}
 * reads the file again and {@code reset} goes back to what the mod ships without touching the file. Runs on the server thread.
 */
public final class BasiliskTuningCommand {
    private BasiliskTuningCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // Operators only, as the rest of /abyss.
        dispatcher.register(Commands.literal("abyss").requires(source -> source.hasPermission(2))
                .then(Commands.literal("basilisk")
                        .executes(context -> list(context.getSource()))
                        .then(Commands.literal("save").executes(context -> save(context.getSource())))
                        .then(Commands.literal("reload").executes(context -> reload(context.getSource())))
                        .then(Commands.literal("reset").executes(context -> reset(context.getSource())))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(Arrays.stream(Dial.values()).map(dial -> dial.key), builder))
                                .executes(context -> show(context))
                                .then(Commands.argument("value", DoubleArgumentType.doubleArg())
                                        .executes(context -> set(context))))));
    }

    private static int list(CommandSourceStack source) {
        BasiliskTuning tuning = BasiliskTuning.get();
        for (Dial dial : Dial.values()) {
            source.sendSuccess(() -> Component.literal(describe(tuning, dial)), false);
        }
        return 1;
    }

    private static int show(CommandContext<CommandSourceStack> context) {
        Optional<Dial> dial = dialOf(context);
        if (dial.isEmpty()) {
            return 0;
        }
        String line = describe(BasiliskTuning.get(), dial.get()) + " - " + dial.get().about;
        context.getSource().sendSuccess(() -> Component.literal(line), false);
        return 1;
    }

    private static int set(CommandContext<CommandSourceStack> context) {
        Optional<Dial> dial = dialOf(context);
        if (dial.isEmpty()) {
            return 0;
        }
        double value = DoubleArgumentType.getDouble(context, "value");
        BasiliskTuning tuning = BasiliskTuning.get().with(dial.get(), value);
        BasiliskTuning.set(tuning);
        String clamped = tuning.get(dial.get()) != value ? " (clamped to " + dial.get().least + " to " + dial.get().most + ")" : "";
        context.getSource().sendSuccess(() -> Component.literal(describe(tuning, dial.get()) + clamped + "; not saved yet"), true);
        return 1;
    }

    private static int save(CommandSourceStack source) {
        if (!BasiliskTuning.save(Platform.getConfigFolder())) {
            source.sendFailure(Component.literal("Could not write config/" + BasiliskTuning.FILE + "; see the log"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Saved to config/" + BasiliskTuning.FILE), true);
        return 1;
    }

    private static int reload(CommandSourceStack source) {
        BasiliskTuning.load(Platform.getConfigFolder());
        source.sendSuccess(() -> Component.literal("Read config/" + BasiliskTuning.FILE + ": " + BasiliskTuning.get().turned()), true);
        return 1;
    }

    private static int reset(CommandSourceStack source) {
        BasiliskTuning.set(BasiliskTuning.SHIPPED);
        source.sendSuccess(() -> Component.literal("Back to the shipped values; the file is unchanged"), true);
        return 1;
    }

    private static Optional<Dial> dialOf(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        Optional<Dial> dial = Dial.byKey(name);
        if (dial.isEmpty()) {
            context.getSource().sendFailure(Component.literal("No basilisk value " + name));
        }
        return dial;
    }

    private static String describe(BasiliskTuning tuning, Dial dial) {
        double value = tuning.get(dial);
        return dial.key + " = " + value + (value != dial.shipped ? " (ships " + dial.shipped + ")" : "");
    }
}
