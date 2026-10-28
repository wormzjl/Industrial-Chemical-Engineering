package com.wormzjl.icheme.client.gui.common.kit;

import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * The game's button drawn by a {@link Skin} (GUI_TOOLKIT_PLAN.md section 10): it keeps the vanilla button's clicks,
 * keys, tooltip and narration. A button marked latched reads so ({@link Skin.Press#CURRENT}: the page or the side
 * shown); most latched keys do not act, and one that does acts on the position it shows (the constructor's current kind
 * opens its design). An inactive button that is not latched reads as switched off; a primary one is the confirm button.
 */
public final class SkinButton extends Button {
    private final Supplier<Skin> skin;
    private boolean latched, primary, soft;
    private Skin.Icon icon;

    public SkinButton(int x, int y, int width, int height, Component message, OnPress onPress, Supplier<Skin> skin) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
        this.skin = skin;
    }

    /**
     * A column's button at content slot {@code slot}: active, latched and soft as {@code spec} says, its hint on hover;
     * pressed, it runs {@code action} with its key.
     */
    public static SkinButton of(Column.Button spec, Rect slot, java.util.function.Consumer<String> action, Supplier<Skin> skin) {
        var b = new SkinButton(slot.x(), slot.y(), slot.w(), slot.h(), Component.literal(spec.text()), p -> action.accept(spec.key()), skin)
                .latched(spec.latched()).soft(spec.soft());
        b.active = spec.active();
        if (spec.hint() != null) b.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(spec.hint())));
        return b;
    }

    /** It reads as latched (the page or the side shown), not as switched off, active or not. */
    public SkinButton latched(boolean value) {
        latched = value;
        return this;
    }

    /** The confirm button (Apply). */
    public SkinButton primary(boolean value) {
        primary = value;
        return this;
    }

    /** A display key (a unit, a basis): it changes what the screens show, not the plant ({@link Skin#softKey}). */
    public SkinButton soft(boolean value) {
        soft = value;
        return this;
    }

    /** A sign on its face instead of its message; the message stays for narration ({@link Skin#iconKey}). */
    public SkinButton icon(Skin.Icon value) {
        icon = value;
        return this;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        var canvas = new GuiCanvas(g, Minecraft.getInstance().font, 0, 0);
        var r = new Rect(getX(), getY(), width, height);
        if (icon != null) {
            skin.get().iconKey(canvas, r, icon, isHoveredOrFocused());
            return;
        }
        if (soft) {
            skin.get().softKey(canvas, r, getMessage().getString(), isHoveredOrFocused());
            return;
        }
        var press = latched ? Skin.Press.CURRENT : !active ? Skin.Press.OFF : primary ? Skin.Press.PRIMARY : Skin.Press.REST;
        skin.get().button(canvas, r, getMessage().getString(), press, isHoveredOrFocused());
    }
}
