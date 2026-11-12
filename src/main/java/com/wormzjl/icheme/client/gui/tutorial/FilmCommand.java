package com.wormzjl.icheme.client.gui.tutorial;

import com.wormzjl.icheme.science.tutorial.films.Films;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * The client command {@code /icheme-film <name>} that opens a film's screen (batch 2026-11-12-tutorial-system). For
 * phase P0 only, so the owner can open the first film; the field guide (P1) opens films from its pages and replaces it.
 * It runs on the client and sends nothing.
 */
public final class FilmCommand {
    private FilmCommand() {
    }

    public static void register(RegisterClientCommandsEvent event) {
        var root = Commands.literal("icheme-film");
        for (var name : Films.names())
            root.then(Commands.literal(name).executes(context -> {
                var scene = Films.build(name);
                // After the chat screen has closed.
                var mc = Minecraft.getInstance();
                mc.tell(() -> mc.setScreen(new FilmScreen(scene)));
                return 1;
            }));
        event.getDispatcher().register(root);
    }
}
