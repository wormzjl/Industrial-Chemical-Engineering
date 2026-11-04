package com.wormzjl.icheme.compat.create;

import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.wormzjl.icheme.science.machine.CreateCoupling;
import com.wormzjl.icheme.world.level.block.ShaftParts;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The Create coupling's Create side (W6b, form A, D40): a Create stress consumer whose impact the engine sets.
 * <ul>
 * <li>Create's speed and spare stress reach the engine as queued events ({@link ShaftParts#createInput}) when they move
 * past {@link CreateCoupling#moved}: from {@link #onSpeedChanged}, {@link #updateFromNetwork}, and once when the block
 * entity starts ({@link #initialize}, which a load or a placement runs). Nothing here steps a train.</li>
 * <li>The stress impact (su per rpm) comes back from the engine's step ({@link #stressImpact}): its value is the engine's,
 * {@link #calculateStressApplied} only hands it to Create, and it is cut to what the network spares at the moment it
 * lands, so Create never overstresses because of the coupling.</li>
 * </ul>
 * It saves nothing of its own: Create keeps its last impact in its own tag ({@code AddedStress}).
 */
public final class CreateCouplingBlockEntity extends KineticBlockEntity implements ShaftParts.Coupling {
    /** The engine's impact, su per rpm. */
    private float impact;
    /** What the engine was last told: |rpm| and the stress the coupling may take, su (NaN: nothing yet). */
    private double sentRpm = Double.NaN, sentStress = Double.NaN;

    public CreateCouplingBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public float calculateStressApplied() {
        lastStressApplied = impact;
        return impact;
    }

    @Override
    public void initialize() {
        super.initialize();
        report();
    }

    @Override
    public void onSpeedChanged(float previousSpeed) {
        super.onSpeedChanged(previousSpeed);
        report();
    }

    @Override
    public void updateFromNetwork(float maxStress, float currentStress, int networkSize) {
        super.updateFromNetwork(maxStress, currentStress, networkSize);
        report();
    }

    /** An unloaded coupling turns nothing: its train stands until it loads again (reading A150). */
    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        if (level != null && !level.isClientSide()) ShaftParts.createInput(level, worldPosition, 0, 0);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        // Create's own saved impact, so its network's sums agree until the engine sends the next one.
        impact = Math.max(0, lastStressApplied);
    }

    /** Queues Create's speed and spare stress for the engine when they moved past their deadbands. */
    private void report() {
        if (level == null || level.isClientSide()) return;
        double rpm = Math.abs(getSpeed()), stress = rpm > 0 ? spare() : 0;
        if (!CreateCoupling.moved(sentRpm, sentStress, rpm, stress)) return;
        sentRpm = rpm;
        sentStress = stress;
        ShaftParts.createInput(level, worldPosition, rpm, stress);
    }

    /** The stress the coupling may take, su: the network's capacity less its stress, plus the coupling's own draw. */
    private double spare() {
        if (!IRotate.StressImpact.isEnabled()) return CreateCoupling.UNLIMITED_STRESS;
        if (!hasNetwork()) return 0;
        return Math.max(0, (double) capacity - stress + (double) impact * Math.abs(getTheoreticalSpeed()));
    }

    /** The engine's impact (W6b), cut to what the network spares now; Create's network takes it at once. */
    @Override
    public void stressImpact(double suPerRpm) {
        if (level == null || level.isClientSide()) return;
        double next = Math.max(0, suPerRpm), rpm = Math.abs(getTheoreticalSpeed());
        if (rpm > 0 && IRotate.StressImpact.isEnabled() && hasNetwork())
            next = Math.max(0, Math.min(next, ((double) capacity - stress) / rpm * CreateCoupling.SPARE_SHARE + impact));
        if ((float) next == impact) return;
        impact = (float) next;
        lastStressApplied = impact;
        if (hasNetwork()) getOrCreateNetwork().updateStressFor(this, impact);
        setChanged();
    }

    /** The impact Create holds for the coupling now, su per rpm (the GameTest's read). */
    public float impact() {
        return impact;
    }
}
