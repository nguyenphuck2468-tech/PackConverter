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
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.geysermc.pack.converter.util;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/** Creates a deterministic content fingerprint for a resource tree. */
public final class ResourceFingerprint {
    private static final String ALGORITHM = "SHA-256";
    private static final int BUFFER_SIZE = 16 * 1024;

    private ResourceFingerprint() { }

    public static @NotNull String sha256(@NotNull Path root) throws IOException {
        if (!Files.isDirectory(root)) throw new IOException("Not a resource directory: " + root);
        MessageDigest digest = newDigest();
        Map<String, Path> files = new TreeMap<>();
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile).forEach(path ->
                    files.put(root.relativize(path).toString().replace('\\', '/'), path));
        }
        byte[] buffer = new byte[BUFFER_SIZE];
        for (Map.Entry<String, Path> entry : files.entrySet()) {
            byte[] name = entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            digest.update((byte) 0x01);
            digest.update(name);
            digest.update((byte) 0x00);
            try (InputStream input = Files.newInputStream(entry.getValue())) {
                int read;
                while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            digest.update((byte) 0x02);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(ALGORITHM + " is required by the Java runtime", exception);
        }
    }
}
