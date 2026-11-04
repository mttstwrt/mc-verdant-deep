package dev.syrval.verdantdeep.basilisk;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.syrval.verdantdeep.VerdantDeep;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.util.RandomSource;

/**
 * The basilisk's live tuning: how often and where it spawns, how it patrols and rests, and how it fights. Each is a named number
 * the owner turns while the game runs ({@code /abyss basilisk}, see {@link BasiliskTuningCommand}) and keeps in
 * {@code verdant_deep_basilisk.json} in the game's config folder, which is read when a server starts. Distances in combat are
 * for the smallest basilisk; bigger ones multiply them by their reach ({@code BasiliskSize.reachMultiplier}).
 *
 * <p>Threads: the values in force are one immutable snapshot behind a volatile reference. The server thread replaces it (file
 * load and commands); the server thread and chunk generation's spawn step read it.
 */
public final class BasiliskTuning {
    static final String FILE = VerdantDeep.MOD_ID + "_basilisk.json";
    private static final String ABOUT = "Basilisk tuning. Set live with /abyss basilisk <name> <value>; /abyss basilisk save writes "
            + "this file, /abyss basilisk reload reads it again. Times are in seconds and distances in blocks.";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Every number there is, with what the mod ships and the range it may be set in. */
    public enum Dial {
        // Spawning. A spawn attempt in a hole is kept with a chance that grows from the rim's to the floor's.
        SPAWN_CHANCE_RIM(0.15, 0, 1, "chance a spawn attempt just below the rim is kept"),
        SPAWN_CHANCE_FLOOR(0.6, 0, 1, "chance a spawn attempt at the floor is kept"),
        SPAWN_BELOW_RIM(3, 0, 64, "blocks under the mouth's edge where spawning begins"),
        SPAWN_SPACING(32, 0, 128, "blocks around a spawn that must hold no other basilisk"),
        SPAWN_ASLEEP(0.3, 0, 1, "chance a natural spawn starts asleep"),
        // Patrols and rest.
        PATROL_RADIUS(28, 4, 96, "blocks around its den that a basilisk patrols"),
        PATROL_LEG(16, 4, 48, "longest single walk between two patrol stops"),
        PATROL_PAUSE_MIN(2, 0, 60, "shortest pause at a patrol stop, seconds"),
        PATROL_PAUSE_MAX(6, 0, 60, "longest pause at a patrol stop, seconds"),
        PATROL_SPEED(0.6, 0.1, 2, "patrol speed, times its walking speed"),
        AWAKE_MIN(180, 10, 3600, "shortest time awake before it heads to its den to rest, seconds"),
        AWAKE_MAX(360, 10, 3600, "longest time awake, seconds"),
        REST_MIN(60, 5, 3600, "shortest rest, seconds"),
        REST_MAX(150, 5, 3600, "longest rest, seconds"),
        // Combat: circle between strikes, strike once, retreat, circle again.
        CIRCLE_MIN(6, 2, 48, "nearest it circles its target"),
        CIRCLE_MAX(16, 2, 48, "farthest it circles its target"),
        CIRCLE_SPEED(1, 0.2, 3, "circling speed, times its engaged speed"),
        STRIKE_INTERVAL_MIN(4, 0.5, 60, "shortest time between strikes, seconds"),
        STRIKE_INTERVAL_MAX(12, 0.5, 60, "longest time between strikes, seconds"),
        BITE_COOLDOWN(3, 0.5, 30, "shortest time between snaps at a target that comes too close, seconds"),
        RETREAT_DISTANCE(7, 2, 32, "how far from its target it retreats after a strike"),
        RETREAT_TIME(3, 0.5, 20, "longest a retreat lasts, seconds"),
        RETREAT_SPEED(1.35, 0.2, 3, "retreat speed, times its engaged speed"),
        COVER(0.6, 0, 1, "how strongly it prefers spots its target cannot see while circling and retreating");

        public final String key = name().toLowerCase(Locale.ROOT);
        public final double shipped;
        public final double least;
        public final double most;
        public final String about;

        Dial(double shipped, double least, double most, String about) {
            this.shipped = shipped;
            this.least = least;
            this.most = most;
            this.about = about;
        }

        public static Optional<Dial> byKey(String key) {
            return Arrays.stream(values()).filter(dial -> dial.key.equals(key)).findFirst();
        }
    }

    public static final BasiliskTuning SHIPPED = new BasiliskTuning(Arrays.stream(Dial.values()).mapToDouble(dial -> dial.shipped).toArray());

    private static volatile BasiliskTuning current = SHIPPED;

    private final double[] values;

    private BasiliskTuning(double[] values) {
        this.values = values;
    }

    /** The values in force. */
    public static BasiliskTuning get() {
        return current;
    }

    static void set(BasiliskTuning tuning) {
        current = tuning;
    }

    public double get(Dial dial) {
        return values[dial.ordinal()];
    }

    /** A copy with one value changed, clamped to its range. */
    BasiliskTuning with(Dial dial, double value) {
        double[] changed = values.clone();
        changed[dial.ordinal()] = Math.clamp(value, dial.least, dial.most);
        return new BasiliskTuning(changed);
    }

    /** A random value between two dials, whichever way round they are set. */
    public double between(Dial low, Dial high, RandomSource random) {
        double a = get(low), b = get(high);
        return Math.min(a, b) + random.nextDouble() * Math.abs(b - a);
    }

    /** {@link #between} for two dials in seconds, in ticks. */
    public int ticksBetween(Dial low, Dial high, RandomSource random) {
        return (int) Math.round(between(low, high, random) * 20);
    }

    public int ticks(Dial seconds) {
        return (int) Math.round(get(seconds) * 20);
    }

    /**
     * Reads {@code folder}'s file into the values in force. A file that is not there, or lacks a name, is written with every value
     * it has kept; one that cannot be read is left for the owner to mend and the shipped values are used.
     */
    public static void load(Path folder) {
        Path file = folder.resolve(FILE);
        if (!Files.exists(file)) {
            set(SHIPPED);
            save(folder);
            return;
        }
        JsonObject json;
        try {
            json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        } catch (IOException | JsonParseException | IllegalStateException e) {
            VerdantDeep.LOGGER.error("{} could not be read ({}); the basilisk uses the values the mod ships", file, e.getMessage());
            set(SHIPPED);
            return;
        }
        BasiliskTuning read = SHIPPED;
        boolean lacksADial = false;
        for (Dial dial : Dial.values()) {
            JsonElement given = json.get(dial.key);
            if (given == null || !given.isJsonPrimitive() || !given.getAsJsonPrimitive().isNumber()) {
                lacksADial = true;
                if (given != null) {
                    VerdantDeep.LOGGER.warn("{}: {} is not a number; it is {}", file, dial.key, dial.shipped);
                }
                continue;
            }
            double value = given.getAsDouble();
            if (value < dial.least || value > dial.most) {
                VerdantDeep.LOGGER.warn("{}: {} {} is outside {} to {} and is clamped", file, dial.key, value, dial.least, dial.most);
            }
            read = read.with(dial, value);
        }
        set(read);
        if (lacksADial) {
            save(folder);
        }
        VerdantDeep.LOGGER.info("Basilisk tuning: {}", read.turned());
    }

    /** Writes the values in force to {@code folder}'s file; false if it could not be written. */
    static boolean save(Path folder) {
        Path file = folder.resolve(FILE);
        BasiliskTuning tuning = current;
        var json = new JsonObject();
        json.addProperty("about", ABOUT);
        for (Dial dial : Dial.values()) {
            json.addProperty(dial.key, tuning.get(dial));
        }
        try {
            Files.createDirectories(folder);
            Files.writeString(file, GSON.toJson(json) + "\n");
            return true;
        } catch (IOException e) {
            VerdantDeep.LOGGER.warn("{} could not be written ({})", file, e.getMessage());
            return false;
        }
    }

    /** The values that differ from what the mod ships, as {@code name=value}, or "as shipped". */
    String turned() {
        var out = new StringBuilder();
        for (Dial dial : Dial.values()) {
            if (get(dial) != dial.shipped) {
                out.append(out.isEmpty() ? "" : ", ").append(dial.key).append('=').append(get(dial));
            }
        }
        return out.isEmpty() ? "as shipped" : out.toString();
    }
}
