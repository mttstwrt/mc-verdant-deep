package dev.syrval.verdantdeep.ravine;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Where the sun reaches the discs of a cone, for the growth a theme marks {@code sun} or {@code shade} (see
 * {@link DiscTheme.Light}). Light falls straight down through the hole's mouth, so a column of an underground disc has the
 * sun when it lies under the mouth or within {@code margin} blocks past its edge, and is in shade from {@code fade} blocks
 * further out, the sun thinning steadily between. Discs above do not shade those below: looking down the hole, the shaft
 * reads as lit all the way to the floor. Surface shelves have the sun everywhere (see {@link DiscLayout#sunAt}).
 */
public record SunSettings(float margin, float fade) {
    static final float MOST = 256;
    static final Codec<SunSettings> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.floatRange(0, MOST).fieldOf("margin").forGetter(SunSettings::margin),
            Codec.floatRange(0, MOST).fieldOf("fade").forGetter(SunSettings::fade)
    ).apply(i, SunSettings::new));

    /** The share of the sun a column has, from 0 in shade to 1, {@code pastMouth} blocks further from the axis than the mouth's edge. */
    double shareAt(double pastMouth) {
        double out = pastMouth - margin;
        if (out <= 0) {
            return 1;
        }
        return out >= fade ? 0 : 1 - out / fade;
    }
}
