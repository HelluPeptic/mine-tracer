package com.flowframe;

/*
MineTracer Mod Features:

- Block break/place logging with NBT support
- Container interaction logging (chest, furnace, etc.)
- Sign edit logging
- Kill event logging
- Advanced query system with filters (user, time, range, action, item)
- Rollback functionality to undo actions
- Inspector mode for real-time block inspection
- Permission-based commands for lookup, rollback, and inspection
*/

import net.fabricmc.api.ModInitializer;
import com.flowframe.features.minetracer.MineTracerCommand;
import com.flowframe.features.minetracer.ChestEventListener;
import com.flowframe.features.minetracer.MineTracer;

public class FlowframeMod implements ModInitializer {
    @Override
    public void onInitialize() {
        System.out.println("[MINETRACER] Initializing MineTracer mod");
        MineTracerCommand.register();
        ChestEventListener.register();
        MineTracer.register();
        // Register MineTracer log storage lifecycle hooks
        com.flowframe.features.minetracer.LogStorage.registerServerLifecycle();
        System.out.println("[MINETRACER] MineTracer mod initialized");
    }
}
