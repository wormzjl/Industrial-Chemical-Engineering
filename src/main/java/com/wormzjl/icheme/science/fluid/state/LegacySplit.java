package com.wormzjl.icheme.science.fluid.state;

/**
 * A node's split in the fields a vessel's facts carry since before W3b, whose meaning never changes (facts schema 22
 * appends the two liquid slots beside them; the checkpoint carries the three slots since state format 48): the compounds of both liquids, the compounds of the vapour, the water of the liquids and of
 * the vapour, and the vapour's compound partial pressure {@code P (1 - y_w)} (the meaning that field had). Amounts are
 * kept exactly; a second liquid's composition is not, and the reader ({@code FluidThermodynamics.fromLegacySplit}) states the compounds'
 * liquid and the water as two liquids, which the next phase check and refresh equilibrate.
 */
public record LegacySplit(double[] liquid,double[] vapor,double waterLiquid,double waterVapor,double hydrocarbonPressure) {}
