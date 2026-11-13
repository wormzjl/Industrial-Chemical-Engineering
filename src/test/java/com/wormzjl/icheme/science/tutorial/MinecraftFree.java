package com.wormzjl.icheme.science.tutorial;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;

/** A class loader over the test class path that refuses the game's classes: a class that reaches one fails to load in it. */
final class MinecraftFree extends URLClassLoader {
    MinecraftFree() throws Exception {
        super(urls(), ClassLoader.getPlatformClassLoader());
    }

    private static URL[] urls() throws Exception {
        var urls = new ArrayList<URL>();
        for (var entry : System.getProperty("java.class.path").split(File.pathSeparator)) urls.add(new File(entry).toURI().toURL());
        return urls.toArray(URL[]::new);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.startsWith("net.minecraft.") || name.startsWith("com.mojang.") || name.startsWith("net.neoforged.") || name.startsWith("org.lwjgl."))
            throw new ClassNotFoundException("A tutorial class may not load " + name);
        return super.loadClass(name, resolve);
    }
}
