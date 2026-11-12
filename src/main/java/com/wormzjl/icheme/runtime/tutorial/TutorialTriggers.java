package com.wormzjl.icheme.runtime.tutorial;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.Optional;

/**
 * The tutorial's advancement criteria (batch 2026-11-12-tutorial-system, P1; owner decision D2: progress is vanilla
 * advancements, no save format of the mod's own). {@code icheme:guide_step} fires only from the client's report
 * ({@code TutorialNetwork.StepDone}) for the step it names; {@code icheme:equipment_built} fires from the server's own
 * event, a structure built from the constructor, with an optional kind. Each mirrors the game's
 * {@code ConstructBeaconTrigger}: an instance record with its optional player predicate and a record codec. No client
 * class: loads on a dedicated server.
 */
public final class TutorialTriggers {
    private static final DeferredRegister<CriterionTrigger<?>> TRIGGERS = DeferredRegister.create(Registries.TRIGGER_TYPE, IChemE.MOD_ID);
    public static final DeferredHolder<CriterionTrigger<?>, GuideStep> GUIDE_STEP = TRIGGERS.register("guide_step", GuideStep::new);
    public static final DeferredHolder<CriterionTrigger<?>, EquipmentBuilt> EQUIPMENT_BUILT = TRIGGERS.register("equipment_built", EquipmentBuilt::new);

    private TutorialTriggers() {
    }

    public static void register(IEventBus modEventBus) {
        TRIGGERS.register(modEventBus);
    }

    /** A guide step the client reports done: the instance's {@code step} ({@code <chapter>/<step>}) must match. */
    public static final class GuideStep extends SimpleCriterionTrigger<GuideStep.Instance> {
        @Override
        public Codec<Instance> codec() {
            return Instance.CODEC;
        }

        public void trigger(ServerPlayer player, String step) {
            trigger(player, instance -> instance.step().equals(step));
        }

        public record Instance(Optional<ContextAwarePredicate> player, String step) implements SimpleCriterionTrigger.SimpleInstance {
            public static final Codec<Instance> CODEC = RecordCodecBuilder.create(i -> i.group(
                    EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player),
                    Codec.STRING.fieldOf("step").forGetter(Instance::step)
            ).apply(i, Instance::new));
        }
    }

    /** A structure the player built from the constructor: any kind, or the instance's {@code kind} (an {@code EquipmentShape.Kind} name). */
    public static final class EquipmentBuilt extends SimpleCriterionTrigger<EquipmentBuilt.Instance> {
        @Override
        public Codec<Instance> codec() {
            return Instance.CODEC;
        }

        public void trigger(ServerPlayer player, EquipmentShape.Kind kind) {
            trigger(player, instance -> instance.kind().map(kind.name()::equals).orElse(true));
        }

        public record Instance(Optional<ContextAwarePredicate> player, Optional<String> kind) implements SimpleCriterionTrigger.SimpleInstance {
            public static final Codec<Instance> CODEC = RecordCodecBuilder.create(i -> i.group(
                    EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player),
                    Codec.STRING.optionalFieldOf("kind").forGetter(Instance::kind)
            ).apply(i, Instance::new));
        }
    }
}
