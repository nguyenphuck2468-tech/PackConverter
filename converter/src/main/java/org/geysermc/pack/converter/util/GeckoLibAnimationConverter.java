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
import java.util.Locale;
import java.util.Map;

/**
 * Converts the data-only subset of GeckoLib animation JSON into Bedrock actor
 * animations. This intentionally does not load GeckoLib or any mod classes.
 *
 * <p>The generated animation files are a reusable Bedrock representation. An
 * entity/geometry converter can later attach them to the appropriate entity
 * definition without coupling this converter to a particular mod loader.</p>
 */
public final class GeckoLibAnimationConverter {
    private GeckoLibAnimationConverter() {
    }

    public static int convert(@NotNull Path sourceRoot, @NotNull Path outputRoot,
                              @NotNull LogListener logListener) throws IOException {
        int converted = 0;
        try (var paths = Files.walk(sourceRoot)) {
            for (Path file : paths.filter(GeckoLibAnimationConverter::looksLikeAnimationFile).toList()) {
                try {
                    if (convertFile(sourceRoot, file, outputRoot)) converted++;
                } catch (RuntimeException | IOException exception) {
                    logListener.warn("Failed to convert animation " + sourceRoot.relativize(file) + ": " + exception.getMessage());
                }
            }
        }
        if (converted > 0) {
            logListener.info("Converted " + converted + " GeckoLib animation file(s) to Bedrock actor animations.");
        }
        return converted;
    }

    private static boolean looksLikeAnimationFile(Path path) {
        String normalized = path.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        return normalized.endsWith("/animations.json") || normalized.contains("/animations/") && normalized.endsWith(".json");
    }

    private static boolean convertFile(Path sourceRoot, Path source, Path outputRoot) throws IOException {
        JsonElement parsed = JsonParser.parseString(Files.readString(source, StandardCharsets.UTF_8));
        if (!parsed.isJsonObject()) return false;
        JsonObject root = parsed.getAsJsonObject();
        JsonObject animations = root.has("animations") && root.get("animations").isJsonObject()
                ? root.getAsJsonObject("animations") : null;
        if (animations == null || animations.entrySet().isEmpty()) return false;

        String relative = sourceRoot.relativize(source).toString().replace('\\', '/');
        String baseName = relative.substring(relative.lastIndexOf('/') + 1, relative.length() - ".json".length());
        if (baseName.equals("animations")) {
            baseName = relative.substring(0, relative.length() - ".json".length())
                    .replace('/', '_').replace(' ', '_');
        }

        JsonObject bedrock = new JsonObject();
        bedrock.addProperty("format_version", "1.8.0");
        JsonObject outputAnimations = new JsonObject();
        int count = 0;

        for (Map.Entry<String, JsonElement> entry : animations.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject sourceAnimation = entry.getValue().getAsJsonObject();
            JsonObject targetAnimation = new JsonObject();

            if (sourceAnimation.has("loop")) {
                JsonElement loop = sourceAnimation.get("loop");
                if (loop.isJsonPrimitive() && loop.getAsJsonPrimitive().isBoolean()) {
                    targetAnimation.addProperty("loop", loop.getAsBoolean());
                } else if (loop.isJsonPrimitive()) {
                    String value = loop.getAsString();
                    targetAnimation.addProperty("loop", !"false".equalsIgnoreCase(value));
                }
            }

            double length = sourceAnimation.has("animation_length")
                    ? sourceAnimation.get("animation_length").getAsDouble() : 0.0;
            JsonObject bones = sourceAnimation.has("bones") && sourceAnimation.get("bones").isJsonObject()
                    ? sourceAnimation.getAsJsonObject("bones") : null;
            if (bones != null) {
                JsonObject targetBones = new JsonObject();
                for (Map.Entry<String, JsonElement> boneEntry : bones.entrySet()) {
                    if (!boneEntry.getValue().isJsonObject()) continue;
                    JsonObject sourceBone = boneEntry.getValue().getAsJsonObject();
                    JsonObject targetBone = new JsonObject();
                    copyChannel(sourceBone, targetBone, "rotation");
                    copyChannel(sourceBone, targetBone, "position");
                    copyChannel(sourceBone, targetBone, "scale");
                    if (targetBone.size() > 0) targetBones.add(boneEntry.getKey(), targetBone);
                    length = Math.max(length, maxKeyframeTime(sourceBone));
                }
                if (targetBones.size() > 0) targetAnimation.add("bones", targetBones);
            }

            if (length > 0) targetAnimation.addProperty("animation_length", length);
            if (targetAnimation.size() == 0) continue;
            outputAnimations.add(entry.getKey(), targetAnimation);
            count++;
        }

        if (count == 0) return false;
        bedrock.add("animations", outputAnimations);

        Path output = outputRoot.resolve("animations").resolve(safeName(baseName) + ".json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, bedrock.toString(), StandardCharsets.UTF_8);
        return true;
    }

    private static void copyChannel(JsonObject sourceBone, JsonObject targetBone, String channel) {
        if (!sourceBone.has(channel) || !sourceBone.get(channel).isJsonObject()) return;
        JsonObject sourceChannel = sourceBone.getAsJsonObject(channel);
        JsonObject targetChannel = new JsonObject();
        for (Map.Entry<String, JsonElement> keyframe : sourceChannel.entrySet()) {
            JsonElement value = keyframe.getValue();
            if (value.isJsonArray()) {
                targetChannel.add(keyframe.getKey(), value.deepCopy());
            } else if (value.isJsonObject()) {
                JsonObject object = value.getAsJsonObject();
                if (object.has("vector") && object.get("vector").isJsonArray()) {
                    targetChannel.add(keyframe.getKey(), object.get("vector").deepCopy());
                } else if (object.has("post") && object.get("post").isJsonArray()) {
                    targetChannel.add(keyframe.getKey(), object.get("post").deepCopy());
                }
            }
        }
        if (targetChannel.size() > 0) targetBone.add(channel, targetChannel);
    }

    private static double maxKeyframeTime(JsonObject bone) {
        double max = 0.0;
        for (String channel : new String[]{"rotation", "position", "scale"}) {
            if (!bone.has(channel) || !bone.get(channel).isJsonObject()) continue;
            for (String key : bone.getAsJsonObject(channel).keySet()) {
                try {
                    max = Math.max(max, Double.parseDouble(key));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return max;
    }

    private static String safeName(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
    }
}
