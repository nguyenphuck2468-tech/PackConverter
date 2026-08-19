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
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Creates deterministic content fingerprints for resource trees and ZIP archives. */
public final class ResourceFingerprint {
    private static final String ALGORITHM = "SHA-256";
    private static final int BUFFER_SIZE = 16 * 1024;

    private ResourceFingerprint() { }

    public static @NotNull String sha256(@NotNull Path root) throws IOException {
        if (Files.isDirectory(root)) return hashDirectory(root);
        if (Files.isRegularFile(root)) return hashFile(root);
        throw new IOException("Not a file or resource directory: " + root);
    }

    /** Combines already deterministic fingerprints and a converter context into one cache key. */
    public static @NotNull String context(@NotNull String inputFingerprint,
                                          @NotNull String vanillaFingerprint,
                                          @NotNull String converterVersion) {
        MessageDigest digest = newDigest();
        updateString(digest, inputFingerprint);
        updateString(digest, vanillaFingerprint);
        updateString(digest, converterVersion);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String hashDirectory(Path root) throws IOException {
        MessageDigest digest = newDigest();
        Map<String, Path> files = new TreeMap<>();
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile).forEach(path ->
                    files.put(root.relativize(path).toString().replace('\\', '/'), path));
        }
        byte[] buffer = new byte[BUFFER_SIZE];
        for (Map.Entry<String, Path> entry : files.entrySet()) {
            updateEntry(digest, entry.getKey(), Files.newInputStream(entry.getValue()), buffer);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String hashFile(Path file) throws IOException {
        if (isZipLike(file)) {
            MessageDigest digest = newDigest();
            List<ZipEntry> entries = new ArrayList<>();
            try (ZipFile zip = new ZipFile(file.toFile())) {
                zip.stream().filter(entry -> !entry.isDirectory()).forEach(entries::add);
                entries.sort(java.util.Comparator.comparing(ZipEntry::getName));
                byte[] buffer = new byte[BUFFER_SIZE];
                for (ZipEntry entry : entries) {
                    try (InputStream input = zip.getInputStream(entry)) {
                        updateEntry(digest, entry.getName(), input, buffer);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        }
        MessageDigest digest = newDigest();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static boolean isZipLike(Path file) {
        String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".zip") || name.endsWith(".jar") || name.endsWith(".mcpack");
    }

    private static void updateEntry(MessageDigest digest, String name, InputStream input, byte[] buffer) throws IOException {
        updateString(digest, name);
        int read;
        while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        digest.update((byte) 0x02);
    }

    private static void updateString(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) 0x01);
        digest.update(bytes);
        digest.update((byte) 0x00);
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(ALGORITHM + " is required by the Java runtime", exception);
        }
    }
}
