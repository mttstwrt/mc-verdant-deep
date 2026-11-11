package dev.syrval.verdantdeep.neoforge;

import dev.syrval.verdantdeep.VerdantDeep;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;

@Mod(VerdantDeep.MOD_ID)
public final class VerdantDeepNeoForge {
    public VerdantDeepNeoForge() {
        VerdantDeep.init();
        // A full chunk of a server level, on the server thread: where the holes' gas is placed from.
        NeoForge.EVENT_BUS.addListener((ChunkEvent.Load event) -> {
            if (event.getLevel() instanceof ServerLevel level && event.getChunk() instanceof LevelChunk chunk) {
                VerdantDeep.gas().chunkLoaded(level, chunk.getPos());
            }
        });
    }
}
