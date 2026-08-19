/*
 * Copyright (c) 2026 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software.
 */
package org.geysermc.pack.converter.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.geysermc.pack.bedrock.resource.BedrockResourcePack;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Converts Minecraft .png.mcmeta animations into Bedrock flipbook animations. */
public final class AnimatedTextureConverter {
    private AnimatedTextureConverter() {
    }

    public static int convert(@NotNull Path sourceRoot, @NotNull Path outputRoot,
                              @NotNull BedrockResourcePack pack, @NotNull LogListener logListener,
                              String textureSubdirectory) throws IOException {
        int converted = 0;
        try (var paths = Files.walk(sourceRoot)) {
            for (Path meta : paths.filter(path -> path.toString().endsWith(".png.mcmeta")).toList()) {
                String relativeMeta = sourceRoot.relativize(meta).toString().replace('\\', '/');
                if (!relativeMeta.startsWith("assets/") || !relativeMeta.endsWith(".png.mcmeta")) continue;
                String textureRelative = relativeMeta.substring("assets/".length(), relativeMeta.length() - ".mcmeta".length());
                Path texture = sourceRoot.resolve(relativeMeta.substring(0, relativeMeta.length() - ".mcmeta".length()));
                if (!Files.isRegularFile(texture)) continue;

                try {
                    if (convertOne(textureRelative, texture, meta, outputRoot, pack, textureSubdirectory)) converted++;
                } catch (RuntimeException | IOException ex) {
                    logListener.warn("Failed to convert animated texture " + textureRelative + ": " + ex.getMessage());
                }
            }
        }
        if (converted > 0) logListener.info("Converted " + converted + " animated texture(s) to Bedrock flipbooks.");
        return converted;
    }

    private static boolean convertOne(String textureRelative, Path texture, Path meta,
                                      Path outputRoot, BedrockResourcePack pack,
                                      String textureSubdirectory) throws IOException {
        JsonElement root = JsonParser.parseString(Files.readString(meta));
        if (!root.isJsonObject()) return false;
        JsonObject animation = root.getAsJsonObject().getAsJsonObject("animation");
        if (animation == null) return false;

        BufferedImage image = ImageIO.read(texture.toFile());
        if (image == null) return false;

        int frameWidth = animation.has("width") ? animation.get("width").getAsInt() : image.getWidth();
        int frameHeight = animation.has("height") ? animation.get("height").getAsInt() : frameWidth;
        if (frameWidth <= 0 || frameHeight <= 0 || image.getWidth() < frameWidth || image.getHeight() < frameHeight) return false;

        int columns = image.getWidth() / frameWidth;
        int rows = image.getHeight() / frameHeight;
        int availableFrames = columns * rows;
        if (availableFrames <= 0) return false;

        int defaultTime = Math.max(1, animation.has("frametime") ? animation.get("frametime").getAsInt() : 1);
        List<Frame> frames = parseFrames(animation.get("frames"), availableFrames, defaultTime);
        if (frames.isEmpty()) return false;

        // Bedrock flipbooks use one tick value for the whole entry. Expand
        // Minecraft per-frame durations into repeated frames so timing is kept.
        List<Integer> playback = new ArrayList<>();
        for (Frame frame : frames) {
            int repeats = Math.max(1, frame.time());
            for (int i = 0; i < repeats; i++) playback.add(frame.index());
        }

        BufferedImage sheet = new BufferedImage(frameWidth, frameHeight * playback.size(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = sheet.createGraphics();
        for (int i = 0; i < playback.size(); i++) {
            int index = playback.get(i);
            int x = (index % columns) * frameWidth;
            int y = (index / columns) * frameHeight;
            graphics.drawImage(image, 0, i * frameHeight, frameWidth, (i + 1) * frameHeight,
                    x, y, x + frameWidth, y + frameHeight, null);
        }
        graphics.dispose();

        String relative = textureRelative.substring(0, textureRelative.length() - ".png".length());
        int slash = relative.indexOf('/');
        if (slash <= 0) return false;
        String root = relative.substring(0, slash);
        String value = relative.substring(slash + 1);
        String bedrockRoot = switch (root) {
            case "block" -> "blocks";
            case "item" -> "items";
            case "gui" -> "ui";
            default -> root;
        };
        String mappedValue = value;
        for (String mapped : JsonMappings.getMapping("textures").map(relative)) {
            int mappedSlash = mapped.indexOf('/');
            mappedValue = mappedSlash >= 0 ? mapped.substring(mappedSlash + 1) : mapped;
            break;
        }

        String prefix = textureSubdirectory == null ? "textures/" + bedrockRoot + "/" : "textures/" + bedrockRoot + "/" + textureSubdirectory + "/";
        String outputName = prefix + mappedValue + ".png";
        Path output = outputRoot.resolve(outputName);
        Files.createDirectories(output.getParent());
        ImageIO.write(sheet, "png", output.toFile());

        String atlasTile = bedrockRoot + "/" + mappedValue;
        pack.addFlipbookTexture(atlasTile, outputName.substring(0, outputName.length() - ".png".length()), 1);
        return true;
    }

    private static List<Frame> parseFrames(JsonElement element, int availableFrames, int defaultTime) {
        List<Frame> result = new ArrayList<>();
        if (element == null || element.isJsonNull()) {
            for (int i = 0; i < availableFrames; i++) result.add(new Frame(i, defaultTime));
            return result;
        }
        if (!element.isJsonArray()) return result;
        JsonArray frames = element.getAsJsonArray();
        for (JsonElement frame : frames) {
            int index;
            int time = defaultTime;
            if (frame.isJsonPrimitive()) {
                index = frame.getAsInt();
            } else if (frame.isJsonObject() && frame.getAsJsonObject().has("index")) {
                JsonObject object = frame.getAsJsonObject();
                index = object.get("index").getAsInt();
                if (object.has("time")) time = Math.max(1, object.get("time").getAsInt());
            } else continue;
            if (index >= 0 && index < availableFrames) result.add(new Frame(index, time));
        }
        return result;
    }

    private record Frame(int index, int time) {
    }
}
