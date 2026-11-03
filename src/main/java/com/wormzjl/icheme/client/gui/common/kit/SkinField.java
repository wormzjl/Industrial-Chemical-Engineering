package com.wormzjl.icheme.client.gui.common.kit;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.function.Supplier;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
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
    private Runnable enter, commit;

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

    /** What Enter does while the field has the focus (N1: the name's entry saves the name); none keeps the game's own keys. */
    public SkinField onEnter(Runnable action) {
        enter = action;
        return this;
    }

    /**
     * What ends an edit: runs when the field loses the focus (a click anywhere else, another entry) and on Enter, which
     * also ends the edit where no {@link #onEnter} action is set. A screen whose entries wait for Apply sets none.
     */
    public SkinField onCommit(Runnable action) {
        commit = action;
        return this;
    }

    /**
     * Ends the focused entry's edit when a press at ({@code x}, {@code y}) misses it ({@link EditFocus#ends}). Every kit
     * screen calls it first in {@code mouseClicked}: the game moves the focus only to a widget that takes the click.
     */
    public static void clickOutside(ContainerEventHandler screen, double x, double y) {
        if (screen.getFocused() instanceof SkinField field && field.isFocused()
                && EditFocus.ends(new Rect(field.getX(), field.getY(), field.getWidth(), field.getHeight()), x, y)) screen.setFocused(null);
    }

    @Override
    public void setFocused(boolean focused) {
        boolean was = isFocused();
        super.setFocused(focused);
        if (was && !isFocused() && commit != null) commit.run();
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        boolean enterKey = key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER;
        if (enter != null && isFocused() && enterKey) {
            enter.run();
            return true;
        }
        if (commit != null && isFocused() && enterKey) {
            setFocused(false);
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
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
