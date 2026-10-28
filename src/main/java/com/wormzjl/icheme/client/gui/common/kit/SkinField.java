package com.wormzjl.icheme.client.gui.common.kit;

import java.util.function.Supplier;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * The game's text field drawn by a {@link Skin} (GUI_TOOLKIT_PLAN.md section 10): the skin draws the box and, at rest,
 * the value (a panel: a 7-segment meter); while focused the game draws the text, the cursor and the selection 4 px in,
 * as a bordered field places them. The field is unbordered, so the game draws no sprite; its inner width and its
 * clicks keep the bordered field's 4 px margin.
 */
public final class SkinField extends EditBox {
    private final Supplier<Skin> skin;
    private final Font font;
    private String hint = "";

    public SkinField(Font font, int x, int y, int width, int height, Component message, Supplier<Skin> skin) {
        super(font, x, y, width, height, message);
        this.font = font;
        this.skin = skin;
        setBordered(false);
        setTextShadow(false);
    }

    @Override
    public void setHint(Component hint) {
        super.setHint(hint);
        this.hint = hint.getString();
    }

    @Override
    public int getInnerWidth() {
        return width - 8;
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        super.onClick(mouseX - 4, mouseY);
    }

    @Override
    public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (!isVisible()) return;
        var s = skin.get();
        boolean editing = isFocused();
        s.field(new GuiCanvas(g, font, 0, 0), new Rect(getX(), getY(), width, height), getValue(), hint, editing);
        if (!editing) return;
        setTextColor(s.fieldText());
        g.pose().pushPose();
        g.pose().translate(4, (height - 8) / 2f, 0);
        super.renderWidget(g, mouseX, mouseY, partialTick);
        g.pose().popPose();
    }
}
