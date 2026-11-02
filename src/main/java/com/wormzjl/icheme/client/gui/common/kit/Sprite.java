package com.wormzjl.icheme.client.gui.common.kit;

/**
 * A rectangle of a texture (batch 2026-11-02-process-simulator): the texture's resource name, the rectangle in texture pixels and
 * the texture's size. {@link Canvas#sprite} draws it, tinted, at any size. A text, so the kit's Minecraft-free part can hold one.
 */
public record Sprite(String texture, int u, int v, int w, int h, int atlasWidth, int atlasHeight) {
}
