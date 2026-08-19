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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Scans a mod JAR without loading any mod classes.
 *
 * <p>This deliberately treats the JAR as data. It discovers resource
 * namespaces and resource categories so callers can make conversion decisions
 * before the resources are extracted.</p>
 */
public final class ModResourceScanner {
    private ModResourceScanner() {
    }

    public static @NotNull ModResources scan(@NotNull Path jar) throws IOException {
        List<String> entries = new ArrayList<>();
        List<String> namespaces = new ArrayList<>();
        boolean hasPackMetadata = false;

        try (InputStream input = Files.newInputStream(jar); ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName().replace('\\', '/');
                if (name.equals("pack.mcmeta") || name.equals("pack.png")) {
                    hasPackMetadata = true;
                }
                if (!name.startsWith("assets/")) continue;
                String relative = name.substring("assets/".length());
                int slash = relative.indexOf('/');
                if (slash <= 0) continue;
                String namespace = relative.substring(0, slash);
                if (!namespaces.contains(namespace)) namespaces.add(namespace);
                entries.add(name);
            }
        }

        entries.sort(String::compareTo);
        namespaces.sort(String::compareTo);
        return new ModResources(entries, namespaces, hasPackMetadata);
    }

    public record ModResources(@NotNull List<String> entries,
                               @NotNull List<String> namespaces,
                               boolean hasPackMetadata) {
        public ModResources {
            entries = List.copyOf(entries);
            namespaces = List.copyOf(namespaces);
        }

        public long count(@NotNull String directory) {
            String prefix = "assets/" + directory.toLowerCase(Locale.ROOT) + "/";
            return entries.stream().filter(entry -> entry.startsWith(prefix)).count();
        }

        public boolean hasNamespace(@NotNull String namespace) {
            return namespaces.contains(namespace);
        }
    }
}
