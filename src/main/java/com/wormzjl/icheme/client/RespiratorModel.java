package com.wormzjl.icheme.client;

import com.wormzjl.icheme.IChemE;
import java.util.List;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;

/**
 * The respirator's worn model (batch 2026-11-18-respirator; owner's pick: option C, full face with twin cartridges). The
 * mask's parts ({@link RespiratorGeometry}, generated) hang from the head, so they follow it; every other humanoid part is
 * empty. One model for both slots: the helmet slot's armour layer ({@link RespiratorClient}) and a Curios slot
 * ({@code compat.curios.CuriosRespiratorClient}), with one texture, {@link RespiratorClient#TEXTURE}.
 */
public final class RespiratorModel extends HumanoidModel<LivingEntity> {
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID, "respirator"), "main");

    public RespiratorModel(ModelPart root) {
        super(root);
    }

    public static LayerDefinition createLayer() {
        var mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        PartDefinition head = root.addOrReplaceChild("head", CubeListBuilder.create(), PartPose.ZERO);
        RespiratorGeometry.build(head);
        for (var empty : List.of("hat", "body", "right_arm", "left_arm", "right_leg", "left_leg")) {
            root.addOrReplaceChild(empty, CubeListBuilder.create(), PartPose.ZERO);
        }
        return LayerDefinition.create(mesh, RespiratorGeometry.TEXTURE_WIDTH, RespiratorGeometry.TEXTURE_HEIGHT);
    }
}
