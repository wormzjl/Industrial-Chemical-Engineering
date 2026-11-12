package com.wormzjl.icheme.runtime.tutorial;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.runtime.fluid.FluidView;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.tutorial.guide.AssemblyCondition;
import com.wormzjl.icheme.science.tutorial.guide.ColumnCondition;
import com.wormzjl.icheme.science.tutorial.guide.SheetCondition;
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
 * cached status, a read); {@code icheme:diverter_switched} where the player turns a diverter valve by hand. P4b:
 * {@code icheme:sheet_run} where a process simulator stores a player's sheet with results that ran on it (a run's end on the
 * client sends them; the server reads the stored sheet's settings, a {@link SheetCondition}, and solves nothing);
 * {@code icheme:design_sent} where it stores what the player's Send to block sent. P5 stage B: {@code icheme:assembly_state}
 * and {@code icheme:column_state} where a column's assembly screen gets its view (the scheduled send to its open screen),
 * conditions an {@link AssemblyCondition} and a {@link ColumnCondition}. Each mirrors the game's
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
    public static final DeferredHolder<CriterionTrigger<?>, SheetRun> SHEET_RUN = TRIGGERS.register("sheet_run", SheetRun::new);
    public static final DeferredHolder<CriterionTrigger<?>, Plain> DESIGN_SENT = TRIGGERS.register("design_sent", Plain::new);
    public static final DeferredHolder<CriterionTrigger<?>, AssemblyState> ASSEMBLY_STATE = TRIGGERS.register("assembly_state", AssemblyState::new);
    public static final DeferredHolder<CriterionTrigger<?>, ColumnState> COLUMN_STATE = TRIGGERS.register("column_state", ColumnState::new);

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

    /**
     * A process simulator stored {@code body} (a packed sheet and its results) from {@code player}: where the results ran on that
     * sheet, the sheet's settings meet each instance's condition or not. A read of what was stored; nothing is solved.
     */
    public static void sheetStored(ServerPlayer player, byte[] body) {
        try {
            var contents = com.wormzjl.icheme.science.flowsheet.SheetBody.unpack(body);
            if (!com.wormzjl.icheme.network.SimulatorResults.ranOnBody(contents.results())) return;
            var sheet = com.wormzjl.icheme.science.flowsheet.FlowsheetFile.read(contents.sheet());
            SHEET_RUN.get().trigger(player, sheet);
        } catch (com.wormzjl.icheme.science.flowsheet.SheetBody.Refused | RuntimeException unread) {
            // The store took it already; a body the guide cannot read earns nothing.
        }
    }

    /** A sheet stored with the results of its own run: optionally a fired heater's outlet (the unit {@code heater} names, or any) at most {@code max_heater_outlet} K. */
    public static final class SheetRun extends SimpleCriterionTrigger<SheetRun.Instance> {
        @Override
        public Codec<Instance> codec() {
            return Instance.CODEC;
        }

        public void trigger(ServerPlayer player, com.wormzjl.icheme.science.flowsheet.Flowsheet sheet) {
            trigger(player, instance -> instance.condition().matches(sheet, true));
        }

        public record Instance(Optional<ContextAwarePredicate> player, Optional<Double> maxHeaterOutlet, Optional<String> heater) implements SimpleCriterionTrigger.SimpleInstance {
            public static final Codec<Instance> CODEC = RecordCodecBuilder.create(i -> i.group(
                    EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player),
                    Codec.doubleRange(1, 5000).optionalFieldOf("max_heater_outlet").forGetter(Instance::maxHeaterOutlet),
                    Codec.STRING.optionalFieldOf("heater").forGetter(Instance::heater)
            ).apply(i, Instance::new));

            public SheetCondition condition() {
                return new SheetCondition(maxHeaterOutlet, heater);
            }
        }
    }

    /**
     * A column's assembly view reached the player's open screen: its status for {@code assembly_state}, its Operation view
     * (null before any Start) for {@code column_state}. A read of what was sent.
     */
    public static void assemblyShown(ServerPlayer player, com.wormzjl.icheme.science.assembly.AssemblyView view) {
        if (view == null) return;
        ASSEMBLY_STATE.get().trigger(player, view.status().name());
        if (view.operation() != null) COLUMN_STATE.get().trigger(player, view.operation());
    }

    /** A column's assembly as its screen shows it: optionally one of the statuses named. */
    public static final class AssemblyState extends SimpleCriterionTrigger<AssemblyState.Instance> {
        @Override
        public Codec<Instance> codec() {
            return Instance.CODEC;
        }

        public void trigger(ServerPlayer player, String status) {
            trigger(player, instance -> instance.condition().matches(status));
        }

        public record Instance(Optional<ContextAwarePredicate> player, Optional<List<String>> status) implements SimpleCriterionTrigger.SimpleInstance {
            public static final Codec<Instance> CODEC = RecordCodecBuilder.create(i -> i.group(
                    EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player),
                    Codec.STRING.listOf().optionalFieldOf("status").forGetter(Instance::status)
            ).apply(i, Instance::new));

            public AssemblyCondition condition() {
                return new AssemblyCondition(status);
            }
        }
    }

    /** A running column's Operation view as its screen shows it: optionally one of the phases named, optionally a product leaving. */
    public static final class ColumnState extends SimpleCriterionTrigger<ColumnState.Instance> {
        @Override
        public Codec<Instance> codec() {
            return Instance.CODEC;
        }

        public void trigger(ServerPlayer player, com.wormzjl.icheme.science.column.operation.OperationView view) {
            trigger(player, instance -> instance.condition().matches(view));
        }

        public record Instance(Optional<ContextAwarePredicate> player, Optional<List<String>> phase, Optional<Boolean> product) implements SimpleCriterionTrigger.SimpleInstance {
            public static final Codec<Instance> CODEC = RecordCodecBuilder.create(i -> i.group(
                    EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player),
                    Codec.STRING.listOf().optionalFieldOf("phase").forGetter(Instance::phase),
                    Codec.BOOL.optionalFieldOf("product").forGetter(Instance::product)
            ).apply(i, Instance::new));

            public ColumnCondition condition() {
                return new ColumnCondition(phase, product);
            }
        }
    }

    /** An event with no condition of its own: a firing still's fire set off, a diverter valve turned by hand, a design sent. */
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
