/*
 * Copyright (c) 2026 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is furnished
 * to do so, subject to the following conditions:
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/** Small atomic on-disk cache for conversion fingerprints. */
public final class ConversionCache {
    private static final String FORMAT = "packconverter-cache-v1";
    private final Path file;

    public ConversionCache(@NotNull Path directory) throws IOException {
        Files.createDirectories(directory);
        this.file = directory.resolve("conversion.fingerprint");
    }

    public @NotNull Optional<String> read() throws IOException {
        if (!Files.isRegularFile(file)) return Optional.empty();
        String value = Files.readString(file, StandardCharsets.UTF_8).trim();
        if (!value.startsWith(FORMAT + "\n")) return Optional.empty();
        String fingerprint = value.substring((FORMAT + "\n").length()).trim();
        return fingerprint.matches("[0-9a-f]{64}") ? Optional.of(fingerprint) : Optional.empty();
    }

    public void write(@NotNull String fingerprint) throws IOException {
        if (!fingerprint.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid SHA-256 fingerprint");
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, FORMAT + "\n" + fingerprint + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(temporary, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public void invalidate() throws IOException {
        Files.deleteIfExists(file);
    }
}
