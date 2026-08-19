/*
 * Copyright (c) 2026 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.geysermc.pack.converter.util;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Extracts the resource-pack portion of a Minecraft mod JAR.
 *
 * <p>Mod JARs commonly keep their client resources under {@code assets/} rather
 * than being distributed as standalone Java resource packs. PackConverter can
 * now consume those resources directly without requiring the user to unpack
 * the JAR first.</p>
 */
public final class ModJarExtractor {
    private static final String ASSETS_PREFIX = "assets/";

    private ModJarExtractor() {
    }

    /**
     * Returns whether the supplied path looks like a mod JAR.
     */
    public static boolean isModJar(@NotNull Path path) {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith(".jar") || fileName.endsWith(".jarx");
    }

    /**
     * Extracts resource-pack compatible content from a mod JAR.
     *
     * <p>Only {@code assets/}, {@code pack.mcmeta}, and {@code pack.png} are
     * copied. Everything else in the mod (classes, metadata, mixins, data,
     * signatures, etc.) is deliberately ignored.</p>
     *
     * @param jar the mod JAR
     * @param destination empty destination directory
     * @return the destination directory
     */
    public static @NotNull Path extract(@NotNull Path jar, @NotNull Path destination) throws IOException {
        Files.createDirectories(destination);

        try (InputStream input = Files.newInputStream(jar); ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                if (entry.isDirectory() || !isResourcePackEntry(name)) {
                    continue;
                }

                // Reject absolute paths and traversal before resolving the entry.
                Path target = destination.resolve(name).normalize();
                if (!target.startsWith(destination.toAbsolutePath().normalize())) {
                    throw new IOException("Unsafe mod JAR entry: " + name);
                }

                Path parent = target.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.copy(zip, target);
            }
        }

        return destination;
    }

    private static boolean isResourcePackEntry(String name) {
        return name.equals("pack.mcmeta")
                || name.equals("pack.png")
                || name.startsWith(ASSETS_PREFIX);
    }
}
