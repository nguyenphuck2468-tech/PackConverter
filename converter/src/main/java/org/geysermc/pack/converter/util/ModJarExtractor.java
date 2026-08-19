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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Extracts resource-pack assets from Minecraft mod JARs. */
public final class ModJarExtractor {
    private static final String ASSETS_PREFIX = "assets/";

    private ModJarExtractor() {
    }

    public static boolean isModJar(@NotNull Path path) {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith(".jar") || fileName.endsWith(".jarx");
    }

    /** Returns true when a directory contains at least one mod JAR directly inside it. */
    public static boolean isModDirectory(@NotNull Path path) throws IOException {
        if (!Files.isDirectory(path)) return false;
        try (var files = Files.list(path)) {
            return files.anyMatch(ModJarExtractor::isModJar);
        }
    }

    /** Extract one mod JAR into a resource tree. Existing files are replaced. */
    public static @NotNull Path extract(@NotNull Path jar, @NotNull Path destination) throws IOException {
        Path root = destination.toAbsolutePath().normalize();
        Files.createDirectories(root);

        try (InputStream input = Files.newInputStream(jar); ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                if (entry.isDirectory() || !isResourcePackEntry(name)) continue;

                Path target = root.resolve(name).normalize();
                if (!target.startsWith(root)) {
                    throw new IOException("Unsafe mod JAR entry: " + name);
                }

                Path parent = target.getParent();
                if (parent != null) Files.createDirectories(parent);
                Files.copy(zip, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return root;
    }

    /**
     * Merge all directly contained mod JARs in deterministic filename order.
     * Later JARs override resources supplied by earlier JARs.
     */
    public static @NotNull List<Path> extractAll(@NotNull Path directory, @NotNull Path destination) throws IOException {
        Path root = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) throw new IOException("Not a mod directory: " + directory);

        List<Path> jars;
        try (var files = Files.list(root)) {
            jars = files.filter(Files::isRegularFile)
                    .filter(ModJarExtractor::isModJar)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (jars.isEmpty()) throw new IOException("No mod JARs found in: " + directory);

        Files.createDirectories(destination);
        for (Path jar : jars) extract(jar, destination);
        return List.copyOf(jars);
    }

    private static boolean isResourcePackEntry(String name) {
        return name.equals("pack.mcmeta")
                || name.equals("pack.png")
                || name.startsWith(ASSETS_PREFIX);
    }
}
