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
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Extracts client resource-pack data from Minecraft mod JARs. */
public final class ModJarExtractor {
    private static final String ASSETS_PREFIX = "assets/";

    // Keep malformed or hostile mod archives from consuming unbounded disk space.
    private static final long MAX_ENTRY_SIZE = 128L * 1024 * 1024;
    private static final long MAX_TOTAL_SIZE = 512L * 1024 * 1024;
    private static final int MAX_RESOURCE_ENTRIES = 100_000;

    private ModJarExtractor() {
    }

    /** Immutable result of a deterministic multi-mod extraction. */
    public record ExtractionReport(@NotNull List<Path> mods, int filesExtracted,
                                   long bytesExtracted, @NotNull List<String> collisions) {
        public ExtractionReport {
            mods = List.copyOf(mods);
            collisions = List.copyOf(collisions);
        }
    }

    /** Returns whether the supplied path looks like a mod JAR. */
    public static boolean isModJar(@NotNull Path path) {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith(".jar") || fileName.endsWith(".jarx");
    }

    /** Returns whether a directory contains one or more mod JARs. */
    public static boolean isModDirectory(@NotNull Path path) throws IOException {
        if (!Files.isDirectory(path)) return false;
        try (var files = Files.walk(path)) {
            return files.anyMatch(file -> Files.isRegularFile(file) && isModJar(file));
        }
    }

    /** Extracts one mod JAR into a resource-pack directory. */
    public static @NotNull Path extract(@NotNull Path jar, @NotNull Path destination) throws IOException {
        extractInternal(jar, destination, new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashMap<>(),
                new long[]{0}, new int[]{0});
        return destination.toAbsolutePath().normalize();
    }

    /**
     * Extracts every mod JAR below a directory in deterministic relative-path order.
     * Nested mod folders are supported, which makes this work with common modpack layouts.
     * If multiple mods contain the same resource, the later mod in sorted order wins and
     * the collision is reported with both source JARs instead of being silently hidden.
     */
    public static @NotNull ExtractionReport extractAll(@NotNull Path directory, @NotNull Path destination) throws IOException {
        Path root = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) throw new IOException("Not a mod directory: " + directory);

        List<Path> jars;
        try (var files = Files.walk(root)) {
            jars = files.filter(Files::isRegularFile)
                    .filter(ModJarExtractor::isModJar)
                    .sorted(Comparator.comparing(path -> root.relativize(path).toString().toLowerCase(Locale.ROOT)))
                    .toList();
        }

        if (jars.isEmpty()) throw new IOException("No mod JARs found in: " + directory);

        Files.createDirectories(destination);
        Set<String> extracted = new LinkedHashSet<>();
        Set<String> collisions = new LinkedHashSet<>();
        Map<String, Path> owners = new LinkedHashMap<>();
        long[] bytesExtracted = {0};
        int[] entriesExtracted = {0};
        int filesExtracted = 0;
        for (Path jar : jars) {
            filesExtracted += extractInternal(jar, destination, extracted, collisions, owners,
                    bytesExtracted, entriesExtracted);
        }
        return new ExtractionReport(jars, filesExtracted, bytesExtracted[0], new ArrayList<>(collisions));
    }

    private static int extractInternal(Path jar, Path destination, Set<String> extracted,
                                       Set<String> collisions, Map<String, Path> owners,
                                       long[] bytesExtracted, int[] entriesExtracted) throws IOException {
        Path root = destination.toAbsolutePath().normalize();
        Files.createDirectories(root);
        int count = 0;

        try (InputStream input = Files.newInputStream(jar); ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = normalizeEntryName(entry.getName());
                if (entry.isDirectory() || !isResourcePackEntry(name)) continue;

                if (++entriesExtracted[0] > MAX_RESOURCE_ENTRIES) {
                    throw new IOException("Mod resource entry limit exceeded (" + MAX_RESOURCE_ENTRIES + ")");
                }

                Path target = root.resolve(name).normalize();
                if (!target.startsWith(root)) {
                    throw new IOException("Unsafe mod JAR entry: " + entry.getName());
                }

                Path parent = target.getParent();
                if (parent != null) ensureSafeParent(root, parent);

                Path previousOwner = owners.put(name, jar);
                if (!extracted.add(name)) {
                    String previous = previousOwner == null ? "unknown" : previousOwner.getFileName().toString();
                    collisions.add(name + " (" + previous + " -> " + jar.getFileName() + ")");
                }

                long before = bytesExtracted[0];
                try {
                    long copied = Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                    if (copied > MAX_ENTRY_SIZE || before + copied > MAX_TOTAL_SIZE) {
                        Files.deleteIfExists(target);
                        throw new IOException("Mod resource size limit exceeded while extracting: " + entry.getName());
                    }
                    bytesExtracted[0] += copied;
                } catch (IOException exception) {
                    Files.deleteIfExists(target);
                    throw exception;
                }
                count++;
            }
        }
        return count;
    }

    private static void ensureSafeParent(Path root, Path parent) throws IOException {
        Path relative = root.relativize(parent);
        Path current = root;
        for (Path component : relative) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Refusing to extract through symbolic link: " + current);
            }
        }
        Files.createDirectories(parent);
    }

    private static String normalizeEntryName(String name) throws IOException {
        String normalized = name.replace('\\', '/');
        if (normalized.indexOf('\0') >= 0) {
            throw new IOException("Unsafe NUL byte in mod JAR entry");
        }
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:/.*")) {
            throw new IOException("Unsafe absolute mod JAR entry: " + name);
        }
        for (String component : normalized.split("/")) {
            if (component.equals("..")) {
                throw new IOException("Unsafe parent traversal in mod JAR entry: " + name);
            }
        }
        return normalized;
    }

    private static boolean isResourcePackEntry(String name) {
        return name.equals("pack.mcmeta")
                || name.equals("pack.png")
                || name.startsWith(ASSETS_PREFIX);
    }
}
