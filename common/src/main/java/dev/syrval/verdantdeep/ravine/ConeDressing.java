package dev.syrval.verdantdeep.ravine;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;

/**
 * What a cone holds besides its discs, each part optional: the roots that wind down it (see {@link RootSettings}), the
 * water that falls from disc to disc (see {@link FallSettings}), the curse on going up or down it too fast (see
 * {@link CurseSettings}), the gas of its falls, springs and cavern (see {@link GasSettings}), how far the sun reaches its
 * discs (see {@link SunSettings}) and the bowls that catch what its wall lets out of the terrain (see {@link WallBowlSettings}).
 * In a file they are written beside a hole's other settings, as {@code roots}, {@code falls}, {@code curse}, {@code gas},
 * {@code sun} and {@code wall_bowls}; this only gathers them, since the settings' codec takes sixteen fields and no more.
 */
public record ConeDressing(
        Optional<RootSettings> roots, Optional<FallSettings> falls, Optional<CurseSettings> curse, Optional<GasSettings> gas, Optional<SunSettings> sun,
        Optional<WallBowlSettings> wallBowls) {
    public static final ConeDressing NONE = new ConeDressing(Optional.empty(), Optional.empty());

    static final MapCodec<ConeDressing> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            RootSettings.CODEC.optionalFieldOf("roots").forGetter(ConeDressing::roots),
            FallSettings.CODEC.optionalFieldOf("falls").forGetter(ConeDressing::falls),
            CurseSettings.CODEC.optionalFieldOf("curse").forGetter(ConeDressing::curse),
            GasSettings.CODEC.optionalFieldOf("gas").forGetter(ConeDressing::gas),
            SunSettings.CODEC.optionalFieldOf("sun").forGetter(ConeDressing::sun),
            WallBowlSettings.CODEC.optionalFieldOf("wall_bowls").forGetter(ConeDressing::wallBowls)
    ).apply(i, ConeDressing::new));

    public ConeDressing(Optional<RootSettings> roots, Optional<FallSettings> falls) {
        this(roots, falls, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }
}
