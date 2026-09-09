package org.geysermc.pack.converter.type.entity;

import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.regex.Pattern;

/** Worker entry point. Model constructors and reflection are never run in the converter/server JVM. */
public final class EntityWorker {
    private EntityWorker() { }

    public static void main(String[] args) {
        PrintStream result = System.out;
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        System.setErr(new PrintStream(OutputStream.nullOutputStream()));
        try {
            String[] entries = args[0].split(Pattern.quote(java.io.File.pathSeparator));
            URL[] urls = new URL[entries.length];
            for (int i = 0; i < entries.length; i++) urls[i] = Path.of(entries[i]).toUri().toURL();
            try (var loader = new URLClassLoader(urls, EntityWorker.class.getClassLoader())) {
                Thread.currentThread().setContextClassLoader(loader);
                Class<?> model = Class.forName(args[1], false, loader);
                result.print(args.length > 3 && args[3].equals("layer")
                        ? ModelPartGeometry.read(model, args[2]) : EntityGeometry.read(model, args[2]));
                result.flush();
            }
            // Runtime.halt also handles model-created non-daemon threads and shutdown hooks.
            Runtime.getRuntime().halt(0);
        } catch (Throwable failure) {
            Throwable cause = failure;
            for (int i = 0; i < 8 && cause.getCause() != null; i++) cause = cause.getCause();
            result.print(cause.getClass().getSimpleName() + ": " + cause.getMessage());
            result.flush();
            Runtime.getRuntime().halt(1);
        }
    }
}
