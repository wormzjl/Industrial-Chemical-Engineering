package com.wormzjl.icheme.client.gui.common.kit;

import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * The game's button drawn by a {@link Skin} (GUI_TOOLKIT_PLAN.md section 10): it keeps the vanilla button's clicks,
 * keys, tooltip and narration. An inactive button reads as latched ({@link Skin.Press#CURRENT}: the page or the side
 * shown) when marked so, else as switched off; a primary one is the confirm button.
 */
public final class SkinButton extends Button {
    private final Supplier<Skin> skin;
    private boolean latched, primary;

    public SkinButton(int x, int y, int width, int height, Component message, OnPress onPress, Supplier<Skin> skin) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
        this.skin = skin;
    }

    /** Inactive, it reads as latched (the page or the side shown), not as switched off. */
    public SkinButton latched(boolean value) {
        latched = value;
        return this;
    }

    /** The confirm button (Apply). */
    public SkinButton primary(boolean value) {
        primary = value;
        return this;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        var press = !active ? latched ? Skin.Press.CURRENT : Skin.Press.OFF : primary ? Skin.Press.PRIMARY : Skin.Press.REST;
        skin.get().button(new GuiCanvas(g, Minecraft.getInstance().font, 0, 0), new Rect(getX(), getY(), width, height), getMessage().getString(), press, isHoveredOrFocused());
    }
}
