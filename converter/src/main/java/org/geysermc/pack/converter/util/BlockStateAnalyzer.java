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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Parses Java blockstate variants and multipart definitions without loading game classes. */
public final class BlockStateAnalyzer {
    private BlockStateAnalyzer() { }

    public record ModelRef(@NotNull String model, int x, int y, boolean uvLock) { }
    public record Result(@NotNull List<ModelRef> models, boolean variants, boolean multipart, boolean malformed) { }

    public static @NotNull Result analyze(@NotNull Path file) throws IOException {
        JsonElement root;
        try {
            root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return new Result(List.of(), false, false, true);
        }
        if (!root.isJsonObject()) return new Result(List.of(), false, false, true);
        JsonObject object = root.getAsJsonObject();
        List<ModelRef> models = new ArrayList<>();
        boolean hasVariants = object.has("variants");
        boolean hasMultipart = object.has("multipart");
        if (hasVariants && object.get("variants").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : object.getAsJsonObject("variants").entrySet()) addModels(entry.getValue(), models);
        } else if (hasVariants) return new Result(List.of(), true, hasMultipart, true);
        if (hasMultipart && object.get("multipart").isJsonArray()) {
            for (JsonElement part : object.getAsJsonArray("multipart")) {
                if (!part.isJsonObject()) continue;
                JsonElement apply = part.getAsJsonObject().get("apply");
                if (apply != null) addModels(apply, models);
            }
        } else if (hasMultipart) return new Result(List.copyOf(models), hasVariants, true, true);
        return new Result(List.copyOf(models), hasVariants, hasMultipart, false);
    }

    private static void addModels(JsonElement element, List<ModelRef> out) {
        if (element == null) return;
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (JsonElement child : array) addModels(child, out);
            return;
        }
        if (!element.isJsonObject()) return;
        JsonObject object = element.getAsJsonObject();
        JsonElement model = object.get("model");
        if (model == null || !model.isJsonPrimitive()) return;
        int x = number(object, "x", 0);
        int y = number(object, "y", 0);
        boolean uvLock = object.has("uvlock") && object.get("uvlock").isJsonPrimitive() && object.get("uvlock").getAsBoolean();
        out.add(new ModelRef(model.getAsString(), x, y, uvLock));
    }

    private static int number(JsonObject object, String key, int fallback) {
        try { return object.has(key) ? object.get(key).getAsInt() : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }
}
