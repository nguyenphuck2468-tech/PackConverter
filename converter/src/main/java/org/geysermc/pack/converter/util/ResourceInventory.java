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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Indexes every resource before conversion so specialized converters can share one view. */
public final class ResourceInventory {
    public enum Kind {
        TEXTURE, MODEL, BLOCKSTATE, ITEM_MODEL, ANIMATION, FONT, SOUND,
        PARTICLE, SHADER, MATERIAL, LANGUAGE, ATLAS, TAG, METADATA, OTHER
    }

    public record Resource(@NotNull Path path, @NotNull String relativePath, @NotNull Kind kind) { }

    private final List<Resource> resources;

    private ResourceInventory(@NotNull List<Resource> resources) {
        this.resources = List.copyOf(resources);
    }

    public static @NotNull ResourceInventory scan(@NotNull Path root) throws IOException {
        if (!Files.isDirectory(root)) throw new IOException("Not a resource directory: " + root);
        List<Resource> result = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                String relative = root.relativize(path).toString().replace('\\', '/');
                result.add(new Resource(path, relative, classify(relative)));
            });
        }
        result.sort(Comparator.comparing(Resource::relativePath));
        return new ResourceInventory(result);
    }

    public @NotNull List<Resource> resources() { return resources; }

    public @NotNull List<Resource> of(@NotNull Kind kind) {
        return resources.stream().filter(resource -> resource.kind() == kind).toList();
    }

    private static Kind classify(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".png") || p.endsWith(".jpg") || p.endsWith(".jpeg") || p.endsWith(".tga") || p.endsWith(".webp")) return Kind.TEXTURE;
        if (p.contains("/models/") && p.endsWith(".json")) return Kind.MODEL;
        if (p.contains("/blockstates/") && p.endsWith(".json")) return Kind.BLOCKSTATE;
        if (p.contains("/items/") && p.endsWith(".json")) return Kind.ITEM_MODEL;
        if (p.contains("/animations/") && p.endsWith(".json")) return Kind.ANIMATION;
        if (p.contains("/font/") && p.endsWith(".json")) return Kind.FONT;
        if (p.contains("/sounds/") || p.endsWith("sounds.json")) return Kind.SOUND;
        if (p.contains("/particles/") && p.endsWith(".json")) return Kind.PARTICLE;
        if (p.contains("/shaders/") || p.endsWith(".fsh") || p.endsWith(".vsh")) return Kind.SHADER;
        if (p.contains("/materials/") || p.endsWith("material.bin")) return Kind.MATERIAL;
        if (p.contains("/lang/") && p.endsWith(".json")) return Kind.LANGUAGE;
        if (p.contains("/atlases/") && p.endsWith(".json")) return Kind.ATLAS;
        if (p.contains("/tags/") && p.endsWith(".json")) return Kind.TAG;
        if (p.endsWith("pack.mcmeta") || p.endsWith("pack.png") || p.endsWith(".mcmeta")) return Kind.METADATA;
        return Kind.OTHER;
    }
}
