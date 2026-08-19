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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Resolves Java model parent chains without loading Minecraft or a mod.
 * Child properties override parent properties; nested objects are merged.
 * Cycles are reported instead of causing recursive conversion failures.
 */
public final class ModelInheritanceResolver {
    private final Path root;
    private final Map<String, JsonObject> models = new HashMap<>();

    private ModelInheritanceResolver(@NotNull Path root) {
        this.root = root;
    }

    public static @NotNull ModelInheritanceResolver scan(@NotNull Path root) throws IOException {
        ModelInheritanceResolver resolver = new ModelInheritanceResolver(root);
        Path assets = root.resolve("assets");
        if (!Files.isDirectory(assets)) return resolver;
        try (var namespaces = Files.list(assets)) {
            for (Path namespace : namespaces.filter(Files::isDirectory).toList()) {
                Path models = namespace.resolve("models");
                if (!Files.isDirectory(models)) continue;
                try (var files = Files.walk(models)) {
                    for (Path file : files.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".json")).toList()) {
                        String relative = models.relativize(file).toString().replace('\\', '/');
                        String id = namespace.getFileName() + ":" + relative.substring(0, relative.length() - 5);
                        try {
                            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
                            if (parsed.isJsonObject()) resolver.models.put(id, parsed.getAsJsonObject());
                        } catch (RuntimeException ignored) {
                            // Existing converters will report malformed JSON; indexing must remain non-fatal.
                        }
                    }
                }
            }
        }
        return resolver;
    }

    public @NotNull Resolution resolve(@NotNull String id) {
        return resolve(id, new HashSet<>());
    }

    private Resolution resolve(String id, Set<String> chain) {
        JsonObject model = models.get(id);
        if (model == null) return new Resolution(null, false, false);
        if (!chain.add(id)) return new Resolution(model.deepCopy(), false, true);

        JsonObject result = new JsonObject();
        String parent = model.has("parent") && model.get("parent").isJsonPrimitive()
                ? model.get("parent").getAsString() : null;
        if (parent != null) {
            if (!parent.contains(":")) {
                int colon = id.indexOf(':');
                parent = (colon < 0 ? "minecraft" : id.substring(0, colon)) + ":" + parent;
            }
            Resolution parentResult = resolve(parent, chain);
            if (parentResult.model() != null) result = parentResult.model().deepCopy();
            if (parentResult.cycle()) return new Resolution(merge(result, model), false, true);
        }
        chain.remove(id);
        return new Resolution(merge(result, model), parent != null, false);
    }

    private static JsonObject merge(JsonObject parent, JsonObject child) {
        for (Map.Entry<String, JsonElement> entry : child.entrySet()) {
            JsonElement value = entry.getValue();
            JsonElement existing = parent.get(entry.getKey());
            if (value.isJsonObject() && existing != null && existing.isJsonObject()) {
                parent.add(entry.getKey(), merge(existing.getAsJsonObject().deepCopy(), value.getAsJsonObject()));
            } else {
                parent.add(entry.getKey(), value.deepCopy());
            }
        }
        return parent;
    }

    public record Resolution(JsonObject model, boolean inherited, boolean cycle) { }
}
