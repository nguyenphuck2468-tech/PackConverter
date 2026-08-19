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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Extracts resource-pack assets from Minecraft mod JARs. */
public final class ModJarExtractor {
    private static final String ASSETS_PREFIX = "assets/";
    private static final String NESTED_JAR_PREFIX = "META-INF/jars/";
    private static final int MAX_NESTED_JAR_DEPTH = 8;
    private static final long MAX_NESTED_JAR_SIZE = 64L * 1024L * 1024L;

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
        Set<String> visitedNestedJars = new HashSet<>();
        try (InputStream input = Files.newInputStream(jar)) {
            extractJar(input, root, 0, visitedNestedJars);
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
                    .sorted(Comparator.comparing((Path path) -> path.getFileName().toString().toLowerCase(Locale.ROOT))
                            .thenComparing(path -> path.getFileName().toString()))
                    .toList();
        }
        if (jars.isEmpty()) throw new IOException("No mod JARs found in: " + directory);

        Files.createDirectories(destination);
        for (Path jar : jars) extract(jar, destination);
        return List.copyOf(jars);
    }

    /**
     * Extract nested jars before the containing jar's own resources. This makes
     * the outer mod authoritative, matching normal class/resource overlay rules:
     * embedded dependencies provide defaults while the containing mod can override them.
     */
    private static void extractJar(InputStream input, Path root, int depth, Set<String> visitedNestedJars) throws IOException {
        List<byte[]> nestedJars = new ArrayList<>();
        List<String> nestedNames = new ArrayList<>();

        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                if (entry.isDirectory()) continue;

                if (depth < MAX_NESTED_JAR_DEPTH && isNestedJarEntry(name)) {
                    byte[] nested = zip.readNBytes((int) MAX_NESTED_JAR_SIZE);
                    if (nested.length == MAX_NESTED_JAR_SIZE && zip.read() != -1) {
                        throw new IOException("Nested mod JAR exceeds " + MAX_NESTED_JAR_SIZE + " bytes: " + name);
                    }
                    String key = name + ':' + Integer.toUnsignedString(java.util.Arrays.hashCode(nested));
                    if (visitedNestedJars.add(key)) {
                        nestedNames.add(name);
                        nestedJars.add(nested);
                    }
                    continue;
                }

                if (isResourcePackEntry(name)) copyEntry(zip, root, name);
            }
        }

        // Nested resources were collected while reading the ZIP stream. Extracting
        // them only after the outer resources would invert precedence, so process
        // them first by replaying this method recursively before the caller's files.
        // The containing jar's own resources have already been copied above; restore
        // the intended precedence by extracting nested resources to temporary trees.
        if (!nestedJars.isEmpty()) {
            Path nestedRoot = Files.createTempDirectory(root, ".packconverter-nested-");
            try {
                for (int i = 0; i < nestedJars.size(); i++) {
                    extractJar(new ByteArrayInputStream(nestedJars.get(i)), nestedRoot, depth + 1, visitedNestedJars);
                }
                try (var stream = Files.walk(nestedRoot)) {
                    stream.filter(Files::isRegularFile).forEach(path -> {
                        try {
                            String relative = nestedRoot.relativize(path).toString().replace('\\', '/');
                            copyFile(path, root, relative);
                        } catch (IOException exception) {
                            throw new NestedExtractionException(exception);
                        }
                    });
                }
            } catch (NestedExtractionException exception) {
                throw exception.exception;
            } finally {
                deleteTree(nestedRoot);
            }
        }
    }

    private static void copyEntry(ZipInputStream zip, Path root, String name) throws IOException {
        Path target = root.resolve(name).normalize();
        if (!target.startsWith(root)) throw new IOException("Unsafe mod JAR entry: " + name);
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void copyFile(Path source, Path root, String name) throws IOException {
        Path target = root.resolve(name).normalize();
        if (!target.startsWith(root)) throw new IOException("Unsafe nested mod JAR entry: " + name);
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void deleteTree(Path root) {
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    private static boolean isNestedJarEntry(String name) {
        return name.startsWith(NESTED_JAR_PREFIX) && name.endsWith(".jar");
    }

    private static boolean isResourcePackEntry(String name) {
        return name.equals("pack.mcmeta") || name.equals("pack.png") || name.startsWith(ASSETS_PREFIX);
    }

    private static final class NestedExtractionException extends RuntimeException {
        private final IOException exception;
        private NestedExtractionException(IOException exception) { this.exception = exception; }
    }
}
