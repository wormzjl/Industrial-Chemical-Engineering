package com.wormzjl.icheme.runtime.tutorial;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.runtime.fluid.FluidView;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.tutorial.guide.StillCondition;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.List;
import java.util.Optional;

/**
 * The tutorial's advancement criteria (batch 2026-11-12-tutorial-system, P1; owner decision D2: progress is vanilla
 * advancements, no save format of the mod's own). {@code icheme:guide_step} fires only from the client's report
 * ({@code TutorialNetwork.StepDone}) for the step it names; {@code icheme:equipment_built} fires from the server's own
 * event, a structure built from the constructor, with an optional kind. P2b: {@code icheme:still_state} fires where a
 * still's published view goes to one player (the scheduled publication and the replay at opening), its conditions a
 * {@link StillCondition}; {@code icheme:still_stopped} where the player's screen sets a firing still's fire off (its last
 * cached status, a read); {@code icheme:diverter_switched} where the player turns a diverter valve by hand. Each mirrors the game's
 * {@code ConstructBeaconTrigger}: an instance record with its optional player predicate and a record codec. No client
 * class: loads on a dedicated server.
 */
public final class TutorialTriggers {
    private static final DeferredRegister<CriterionTrigger<?>> TRIGGERS = DeferredRegister.create(Registries.TRIGGER_TYPE, IChemE.MOD_ID);
    public static final DeferredHolder<CriterionTrigger<?>, GuideStep> GUIDE_STEP = TRIGGERS.register("guide_step", GuideStep::new);
    public static final DeferredHolder<CriterionTrigger<?>, EquipmentBuilt> EQUIPMENT_BUILT = TRIGGERS.register("equipment_built", EquipmentBuilt::new);
    public static final DeferredHolder<CriterionTrigger<?>, StillState> STILL_STATE = TRIGGERS.register("still_state", StillState::new);
    public static final DeferredHolder<CriterionTrigger<?>, Plain> STILL_STOPPED = TRIGGERS.register("still_stopped", Plain::new);
    public static final DeferredHolder<CriterionTrigger<?>, Plain> DIVERTER_SWITCHED = TRIGGERS.register("diverter_switched", Plain::new);

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

    /** A still's published view reached one player: the instance's {@link StillCondition} must hold for it. */
    public static void stillShown(ServerPlayer player, FluidView.Still still) {
        STILL_STATE.get().trigger(player, still);
    }

    /** The player's screen set a still's fire off; {@code still} is its last cached view (null for none): a firing one stops. */
    public static void stillFiringOff(ServerPlayer player, FluidView.Still still) {
        if (still != null && StillCondition.firing(still.status())) STILL_STOPPED.get().trigger(player);
    }

    /** A still the player watches, as its published view shows it: optional statuses, a least filled share, a least pot temperature (K) and liquid at the spout. */
    public static final class StillState extends SimpleCriterionTrigger<StillState.Instance> {
        @Override
        public Codec<Instance> codec() {
            return Instance.CODEC;
        }

        public void trigger(ServerPlayer player, FluidView.Still still) {
            trigger(player, instance -> instance.condition().matches(still.status(), still.filled(), still.potTemperature(), still.distillate(), still.stages(), still.reflux(), still.closed()));
        }

        public record Instance(Optional<ContextAwarePredicate> player, Optional<List<String>> status, Optional<Double> minFilled,
                               Optional<Double> minPotTemperature, Optional<Boolean> liquid, Optional<Boolean> column, Optional<Double> minReflux) implements SimpleCriterionTrigger.SimpleInstance {
            public static final Codec<Instance> CODEC = RecordCodecBuilder.create(i -> i.group(
                    EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player),
                    Codec.STRING.listOf().optionalFieldOf("status").forGetter(Instance::status),
                    Codec.doubleRange(0, 1).optionalFieldOf("min_filled").forGetter(Instance::minFilled),
                    Codec.doubleRange(1, 5000).optionalFieldOf("min_pot_temperature").forGetter(Instance::minPotTemperature),
                    Codec.BOOL.optionalFieldOf("liquid").forGetter(Instance::liquid),
                    Codec.BOOL.optionalFieldOf("column").forGetter(Instance::column),
                    Codec.doubleRange(0, 1e4).optionalFieldOf("min_reflux").forGetter(Instance::minReflux)
            ).apply(i, Instance::new));

            public StillCondition condition() {
                return new StillCondition(status, minFilled, minPotTemperature, liquid, column, minReflux);
            }
        }
    }

    /** An event with no condition of its own: a firing still's fire set off, a diverter valve turned by hand. */
    public static final class Plain extends SimpleCriterionTrigger<Plain.Instance> {
        @Override
        public Codec<Instance> codec() {
            return Instance.CODEC;
        }

        public void trigger(ServerPlayer player) {
            trigger(player, instance -> true);
        }

        public record Instance(Optional<ContextAwarePredicate> player) implements SimpleCriterionTrigger.SimpleInstance {
            public static final Codec<Instance> CODEC = RecordCodecBuilder.create(i -> i.group(
                    EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player)
            ).apply(i, Instance::new));
        }
    }

    /**
     * A structure the player built from the constructor: any kind, or the instance's {@code kind} (an {@code EquipmentShape.Kind}
     * name); P4b: with {@code column}, whether a still has its column (the structure's own still options).
     */
    public static final class EquipmentBuilt extends SimpleCriterionTrigger<EquipmentBuilt.Instance> {
        @Override
        public Codec<Instance> codec() {
            return Instance.CODEC;
        }

        public void trigger(ServerPlayer player, EquipmentShape shape) {
            boolean column = shape.still().column();
            trigger(player, instance -> instance.kind().map(shape.kind().name()::equals).orElse(true) && instance.column().map(c -> c == column).orElse(true));
        }

        public record Instance(Optional<ContextAwarePredicate> player, Optional<String> kind, Optional<Boolean> column) implements SimpleCriterionTrigger.SimpleInstance {
            public static final Codec<Instance> CODEC = RecordCodecBuilder.create(i -> i.group(
                    EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player),
                    Codec.STRING.optionalFieldOf("kind").forGetter(Instance::kind),
                    Codec.BOOL.optionalFieldOf("column").forGetter(Instance::column)
            ).apply(i, Instance::new));
        }
    }
}
