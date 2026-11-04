package com.wormzjl.icheme.compat.create;

import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.OrientedRotatingVisual;
import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * The coupling's Create side on a client (W6b): Create's half shaft turning on its facing side, drawn by Create's renderer
 * where Flywheel's backend is off and by Create's oriented rotating visual where it is on, as Create draws its own creative
 * motor. The industrial side is the block model's shaft stub, which does not turn (as the creative motor's).
 */
final class CreateCompatClient {
    private CreateCompatClient() {
    }

    static void register(IEventBus modBus) {
        modBus.addListener((EntityRenderersEvent.RegisterRenderers event) -> event.registerBlockEntityRenderer(CreateCompat.COUPLING_ENTITY.get(), CreateCouplingRenderer::new));
        modBus.addListener((FMLClientSetupEvent event) -> event.enqueueWork(() -> SimpleBlockEntityVisualizer.builder(CreateCompat.COUPLING_ENTITY.get())
                .factory(OrientedRotatingVisual.<CreateCouplingBlockEntity>of(AllPartialModels.SHAFT_HALF)).skipVanillaRender(entity -> false).apply()));
    }
}
