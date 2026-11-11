package dev.syrval.verdantdeep;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.MapCodec;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.event.events.common.TickEvent;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import dev.architectury.registry.registries.DeferredRegister;
import dev.syrval.verdantdeep.basilisk.BasiliskEntities;
import dev.syrval.verdantdeep.basilisk.BasiliskTuning;
import dev.syrval.verdantdeep.basilisk.BasiliskTuningCommand;
import dev.syrval.verdantdeep.basilisk.client.BasiliskClient;
import dev.syrval.verdantdeep.compat.AbyssStatsCommand;
import dev.syrval.verdantdeep.compat.CurseTicker;
import dev.syrval.verdantdeep.compat.DiscRuinsPlacement;
import dev.syrval.verdantdeep.compat.DiscRuinsStructure;
import dev.syrval.verdantdeep.compat.HoleGasPlacer;
import dev.syrval.verdantdeep.compat.NestedProcessorListProcessor;
import dev.syrval.verdantdeep.compat.RavineCentrePlacement;
import dev.syrval.verdantdeep.compat.RavineCityStructure;
import dev.syrval.verdantdeep.compat.SwapBlocksProcessor;
import dev.syrval.verdantdeep.ravine.RavineCarve;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacementType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.slf4j.Logger;

public final class VerdantDeep {
    public static final String MOD_ID = "verdant_deep";
    public static final Logger LOGGER = LogUtils.getLogger();
    private static final HoleGasPlacer GAS = new HoleGasPlacer();

    private VerdantDeep() {}

    /** Called once by each loader entrypoint. */
    public static void init() {
        DeferredRegister<MapCodec<? extends DensityFunction>> densityFunctionTypes =
                DeferredRegister.create(MOD_ID, Registries.DENSITY_FUNCTION_TYPE);
        densityFunctionTypes.register("ravine_carve", () -> RavineCarve.MAP_CODEC);
        densityFunctionTypes.register();

        DeferredRegister<StructureType<?>> structureTypes = DeferredRegister.create(MOD_ID, Registries.STRUCTURE_TYPE);
        structureTypes.register("city", () -> RavineCityStructure.TYPE);
        structureTypes.register("disc_ruins", () -> DiscRuinsStructure.TYPE);
        structureTypes.register();

        DeferredRegister<StructurePlacementType<?>> placementTypes = DeferredRegister.create(MOD_ID, Registries.STRUCTURE_PLACEMENT);
        placementTypes.register("ravine_centre", () -> RavineCentrePlacement.TYPE);
        placementTypes.register("disc_ruins", () -> DiscRuinsPlacement.TYPE);
        placementTypes.register();
        DeferredRegister<StructureProcessorType<?>> processorTypes = DeferredRegister.create(MOD_ID, Registries.STRUCTURE_PROCESSOR);
        processorTypes.register("swap_blocks", () -> SwapBlocksProcessor.TYPE);
        processorTypes.register("processor_list", () -> NestedProcessorListProcessor.TYPE);
        processorTypes.register();

        BasiliskEntities.register();
        if (Platform.getEnvironment() == Env.CLIENT) {
            BasiliskClient.register();
        }

        // The basilisk's tuning is read once per server start; /abyss basilisk changes it live from there.
        LifecycleEvent.SERVER_STARTING.register(server -> BasiliskTuning.load(Platform.getConfigFolder()));
        CommandRegistrationEvent.EVENT.register((dispatcher, registry, selection) -> {
            AbyssStatsCommand.register(dispatcher);
            BasiliskTuningCommand.register(dispatcher);
        });
        registerCurse();
        registerGas();

        LOGGER.info("Verdant Deep initialised (build {})", buildDescription());
    }

    // The curse of descent runs outside world generation, as the gas does; a level without a `curse` block does nothing.
    private static void registerCurse() {
        CurseTicker curse = new CurseTicker();
        TickEvent.PLAYER_POST.register(player -> {
            if (player instanceof ServerPlayer serverPlayer) {
                curse.tick(serverPlayer);
            }
        });
        PlayerEvent.PLAYER_QUIT.register(curse::forget);
        PlayerEvent.PLAYER_RESPAWN.register((player, conqueredEnd, reason) -> curse.forget(player));
        PlayerEvent.CHANGE_DIMENSION.register((player, from, to) -> curse.forget(player));
        LifecycleEvent.SERVER_STOPPED.register(server -> curse.clear());
    }

    // The holes' gas is placed by the server through the gas library, a required dependency, which sends it to the players
    // near it and draws it on their clients. Chunk loads come from the loaders (Architectury has no event for them), through
    // gas().
    private static void registerGas() {
        LifecycleEvent.SERVER_LEVEL_UNLOAD.register(GAS::levelUnloaded);
        LifecycleEvent.SERVER_STOPPED.register(server -> GAS.clear());
    }

    /** What gives the holes their gas: each loader reports to it every chunk of a server level that loads, on the server thread. */
    public static HoleGasPlacer gas() {
        return GAS;
    }

    // The jar carries the commit it was built from, so a log line shows whether a world ran the latest code.
    private static String buildDescription() {
        Properties build = new Properties();
        try (InputStream stream = VerdantDeep.class.getResourceAsStream("/verdant_deep-build.properties")) {
            if (stream != null) {
                build.load(stream);
            }
        } catch (IOException e) {
            LOGGER.warn("Could not read the build stamp", e);
        }
        return build.getProperty("commit", "unknown") + ("true".equals(build.getProperty("dirty")) ? " + uncommitted changes" : "");
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
