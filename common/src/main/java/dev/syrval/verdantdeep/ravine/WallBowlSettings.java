package dev.syrval.verdantdeep.ravine;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.InclusiveRange;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;

/**
 * What becomes of the water and lava of the terrain where a hole's wall cuts into them (aquifers, lava pockets, underground
 * lakes and vanilla's springs). Each place the wall lets a fluid out is an outflow: the leaking blocks that lie together. An
 * outflow of at least {@code minLeak} blocks that falls clear down the wall gets a bowl: a pool of the same fluid a few
 * blocks under it ({@code drop}, then up to {@code search} blocks lower where that does not fit), {@code margin} blocks
 * wider than the fall all round, held by a rock shelf joined to the wall. The lip is flush for water away from the fall and
 * raised for lava. Falling fluid that
 * lands in still fluid does not spread, so the pool holds it however long it runs. Every other outflow, too small, too near
 * something or with nowhere to put a bowl, is plugged with the rock round it, so nothing the wall lets out reaches a disc, a
 * root or a fall's water.
 *
 * @param minLeak       the fewest leaking blocks an outflow has for a bowl; a lone spring is plugged
 * @param drop          how far under the outflow's lowest block the pool lies, if it fits there
 * @param search        how many blocks further down a pool is looked for
 * @param margin        blocks of pool round the falls
 * @param clearance     the least distance between a water bowl, or its fall, and a disc's rock, a root or a fall
 * @param lavaClearance the same for lava, which is kept further off since crowns overhang a disc's rim
 * @param fireClearance the least distance between lava, falling or held, and anything lava sets alight; a water bowl is the
 *                      wall's own rock, a lava bowl is {@code lavaRock}
 * @param lavaRock      what a lava bowl is made of
 */
public record WallBowlSettings(
        int minLeak, InclusiveRange<Integer> drop, int search, int margin, double clearance, double lavaClearance, int fireClearance,
        BlockStateProvider lavaRock) {
    /** The farthest anything is looked for from an outflow, which keeps a bowl within the chunks round the leak's. */
    public static final int MOST_REACH = 12;

    public static final Codec<WallBowlSettings> CODEC = RecordCodecBuilder.<WallBowlSettings>create(i -> i.group(
            Codec.intRange(1, 64).fieldOf("min_leak").forGetter(WallBowlSettings::minLeak),
            InclusiveRange.codec(Codec.intRange(1, MOST_REACH)).fieldOf("drop").forGetter(WallBowlSettings::drop),
            Codec.intRange(0, 24).fieldOf("search").forGetter(WallBowlSettings::search),
            Codec.intRange(1, 4).fieldOf("margin").forGetter(WallBowlSettings::margin),
            Codec.doubleRange(0, 8).fieldOf("clearance").forGetter(WallBowlSettings::clearance),
            Codec.doubleRange(0, 8).fieldOf("lava_clearance").forGetter(WallBowlSettings::lavaClearance),
            Codec.intRange(0, 4).fieldOf("fire_clearance").forGetter(WallBowlSettings::fireClearance),
            BlockStateProvider.CODEC.fieldOf("lava_rock").forGetter(WallBowlSettings::lavaRock)
    ).apply(i, WallBowlSettings::new));
}
