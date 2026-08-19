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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Collects conversion coverage without making unsupported data fatal. */
public final class ConversionDiagnostics {
    public enum Level { CONVERTED, APPROXIMATED, UNSUPPORTED, WARNING }

    public record Entry(@NotNull Level level, @NotNull String resource, @NotNull String message) {
        public Entry {
            Objects.requireNonNull(level);
            Objects.requireNonNull(resource);
            Objects.requireNonNull(message);
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    public void converted(@NotNull String resource, @NotNull String message) {
        entries.add(new Entry(Level.CONVERTED, resource, message));
    }

    public void approximated(@NotNull String resource, @NotNull String message) {
        entries.add(new Entry(Level.APPROXIMATED, resource, message));
    }

    public void unsupported(@NotNull String resource, @NotNull String message) {
        entries.add(new Entry(Level.UNSUPPORTED, resource, message));
    }

    public void warning(@NotNull String resource, @NotNull String message) {
        entries.add(new Entry(Level.WARNING, resource, message));
    }

    public @NotNull List<Entry> entries() {
        return List.copyOf(entries);
    }

    public long count(@NotNull Level level) {
        return entries.stream().filter(entry -> entry.level() == level).count();
    }
}
