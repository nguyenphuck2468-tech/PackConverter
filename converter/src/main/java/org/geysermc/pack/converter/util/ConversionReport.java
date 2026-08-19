/*
 * Copyright (c) 2026 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished, to permit persons to whom the Software is furnished to do so, subject to the following conditions:
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

/** Writes a small machine-readable report alongside the converted pack. */
public final class ConversionReport {
    private ConversionReport() { }

    public static void write(@NotNull Path file, @NotNull ConversionDiagnostics diagnostics) throws IOException {
        StringBuilder json = new StringBuilder("{\n  \"entries\": [\n");
        for (int i = 0; i < diagnostics.entries().size(); i++) {
            ConversionDiagnostics.Entry entry = diagnostics.entries().get(i);
            json.append("    {\"level\":\"").append(escape(entry.level().name()))
                    .append("\",\"resource\":\"").append(escape(entry.resource()))
                    .append("\",\"message\":\"").append(escape(entry.message())).append("\"}");
            if (i + 1 < diagnostics.entries().size()) json.append(',');
            json.append('\n');
        }
        json.append("  ],\n  \"counts\": {")
                .append("\"converted\":").append(diagnostics.count(ConversionDiagnostics.Level.CONVERTED)).append(',')
                .append("\"approximated\":").append(diagnostics.count(ConversionDiagnostics.Level.APPROXIMATED)).append(',')
                .append("\"unsupported\":").append(diagnostics.count(ConversionDiagnostics.Level.UNSUPPORTED)).append(',')
                .append("\"warnings\":").append(diagnostics.count(ConversionDiagnostics.Level.WARNING))
                .append("}\n}\n");
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, json, StandardCharsets.UTF_8);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }
}
