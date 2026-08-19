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
 */
package org.geysermc.pack.converter.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Analyzes Java item model overrides without requiring a Minecraft runtime. */
public final class ModelOverrideAnalyzer {
    private ModelOverrideAnalyzer() { }

    public record Result(int models, int overrides, int predicates, int malformed) { }

    public static @NotNull Result scan(@NotNull Path root, @NotNull ConversionDiagnostics diagnostics) throws IOException {
        Path assets = root.resolve("assets");
        if (!Files.isDirectory(assets)) return new Result(0, 0, 0, 0);
        int models = 0;
        int overrides = 0;
        int predicates = 0;
        int malformed = 0;
        try (var stream = Files.walk(assets)) {
            for (Path file : stream.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".json")).toList()) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                if (!relative.contains("/models/") && !relative.contains("/items/")) continue;
                try {
                    JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
                    if (!parsed.isJsonObject()) continue;
                    JsonObject object = parsed.getAsJsonObject();
                    if (!object.has("overrides") || !object.get("overrides").isJsonArray()) continue;
                    models++;
                    JsonArray array = object.getAsJsonArray("overrides");
                    for (JsonElement element : array) {
                        if (!element.isJsonObject()) continue;
                        JsonObject override = element.getAsJsonObject();
                        if (override.has("model") && override.get("model").isJsonPrimitive()) overrides++;
                        JsonObject predicate = override.has("predicate") && override.get("predicate").isJsonObject()
                                ? override.getAsJsonObject("predicate") : null;
                        if (predicate != null) predicates += predicate.size();
                    }
                    diagnostics.approximated(relative, "Found " + array.size() + " Java model override(s) and " +
                            (array.size() == 1 ? "predicate" : "predicate set") + "; downstream mapping may require item semantics.");
                } catch (RuntimeException exception) {
                    malformed++;
                    diagnostics.warning(relative, "Could not parse model override JSON: " + exception.getMessage());
                }
            }
        }
        return new Result(models, overrides, predicates, malformed);
    }
}
