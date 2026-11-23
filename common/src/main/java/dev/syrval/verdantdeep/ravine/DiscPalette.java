package dev.syrval.verdantdeep.ravine;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;

/**
 * What a disc is made of, as part of its {@link DiscTheme}. The terrain makes every disc out of its own rock; a palette
 * replaces that, part by part, with blocks of its own, and any part it leaves out keeps the terrain's rock. Blocks are vanilla
 * block-state providers, so a part can be one block, a weighted mix, or patches following a noise.
 *
 * @param top       layers counted down from the platform's flat top, the first one outermost
 * @param shade     layers that take the place of the top's where the sun does not reach (see {@link #shadedAt}), counted down
 *                  the same way. Below them the top's own layers go on at the same depth, so a shade layer for the outermost
 *                  block alone changes only that
 * @param underside layers counted up from the platform's bottom face; where the platform is too thin for both, the top's win
 * @param body      the rest of the platform
 * @param stem      the stem, or the root of a hanging disc
 * @param bowlPlants low ground cover this disc lends to nearby plunge pools
 */
public record DiscPalette(List<Layer> top, List<Layer> shade, List<Layer> underside, Optional<BlockStateProvider> body, Stem stem, List<BlockState> bowlPlants) {
    static final DiscPalette UNPAINTED = new DiscPalette(List.of(), List.of(), Optional.empty(), Stem.UNPAINTED);

    /** {@code thickness} blocks of one material. */
    public record Layer(int thickness, BlockStateProvider block) {
        static final Codec<Layer> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.intRange(1, 64).fieldOf("thickness").forGetter(Layer::thickness),
                BlockStateProvider.CODEC.fieldOf("block").forGetter(Layer::block)
        ).apply(i, Layer::new));
    }

    /**
     * @param surface layers counted in from the stem's side, the first one outermost
     * @param core    the rest of the stem
     */
    public record Stem(List<Layer> surface, Optional<BlockStateProvider> core) {
        static final Stem UNPAINTED = new Stem(List.of(), Optional.empty());
        static final Codec<Stem> CODEC = RecordCodecBuilder.create(i -> i.group(
                Layer.CODEC.listOf().optionalFieldOf("surface", List.of()).forGetter(Stem::surface),
                BlockStateProvider.CODEC.optionalFieldOf("core").forGetter(Stem::core)
        ).apply(i, Stem::new));

        public Stem {
            surface = List.copyOf(surface);
        }
    }

    public static final Codec<DiscPalette> CODEC = RecordCodecBuilder.create(i -> i.group(
            Layer.CODEC.listOf().optionalFieldOf("top", List.of()).forGetter(DiscPalette::top),
            Layer.CODEC.listOf().optionalFieldOf("shade", List.of()).forGetter(DiscPalette::shade),
            Layer.CODEC.listOf().optionalFieldOf("underside", List.of()).forGetter(DiscPalette::underside),
            BlockStateProvider.CODEC.optionalFieldOf("body").forGetter(DiscPalette::body),
            Stem.CODEC.optionalFieldOf("stem", Stem.UNPAINTED).forGetter(DiscPalette::stem),
            BlockState.CODEC.listOf().optionalFieldOf("bowl_plants", List.of()).forGetter(DiscPalette::bowlPlants)
    ).apply(i, DiscPalette::new));

    public DiscPalette {
        top = List.copyOf(top);
        shade = List.copyOf(shade);
        underside = List.copyOf(underside);
        bowlPlants = List.copyOf(bowlPlants);
    }

    public DiscPalette(List<Layer> top, List<Layer> shade, List<Layer> underside, Optional<BlockStateProvider> body, Stem stem) {
        this(top, shade, underside, body, stem, List.of());
    }

    /** A palette the same in sun and shade. */
    public DiscPalette(List<Layer> top, List<Layer> underside, Optional<BlockStateProvider> body, Stem stem) {
        this(top, List.of(), underside, body, stem);
    }

    /** The material at a point of a disc in the sun, or empty where this palette leaves the terrain's own rock. */
    Optional<BlockStateProvider> blockAt(DiscPoint point) {
        return blockAt(point, false);
    }

    /** The material at a point of a disc in the sun or in shade, or empty where this palette leaves the terrain's own rock. */
    Optional<BlockStateProvider> blockAt(DiscPoint point, boolean shaded) {
        return switch (point) {
            case DiscPoint.Platform platform -> (shaded ? layerAt(shade, platform.belowTop()) : Optional.<BlockStateProvider>empty())
                    .or(() -> layerAt(top, platform.belowTop()))
                    .or(() -> layerAt(underside, platform.aboveUnderside()))
                    .or(() -> body);
            case DiscPoint.Stem inStem -> layerAt(stem.surface(), inStem.inside()).or(stem::core);
        };
    }

    /** Whether a column of the {@code index}-th disc of {@code layout} takes the shade's layers (see {@link DiscLayout#shadedAt}). */
    boolean shadedAt(long hash, int index, DiscLayout layout, Disc disc, int x, int z) {
        return !shade.isEmpty() && layout.shadedAt(hash, index, disc, x, z);
    }

    /**
     * Whether every one of the platform's highest {@code blocks} blocks is given a material, whatever the platform's thickness.
     * The shade's layers only cover more, never less, so the top's answer holds in shade too.
     */
    boolean coversTop(int blocks) {
        for (int below = 0; below < blocks; below++) {
            // Far from the underside, so only the top's layers and the body can answer.
            if (blockAt(new DiscPoint.Platform(below + 0.5, Double.MAX_VALUE)).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    // A block counts as being in a layer if its centre-line depth is within it, so a thickness of 1 is exactly the outermost block.
    private static Optional<BlockStateProvider> layerAt(List<Layer> layers, double depth) {
        double reach = 0;
        for (Layer layer : layers) {
            reach += layer.thickness();
            if (depth <= reach) {
                return Optional.of(layer.block());
            }
        }
        return Optional.empty();
    }
}
