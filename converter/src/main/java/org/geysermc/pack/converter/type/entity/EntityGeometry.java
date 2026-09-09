package org.geysermc.pack.converter.type.entity;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded Citadel part-tree reader and Bedrock 1.16 cube encoder. No block geometry policy. */
final class EntityGeometry {
    static final int MAX_PARTS = 1024, MAX_CUBES = 16384, MAX_DEPTH = 128;
    private final IdentityHashMap<Object, String> names = new IdentityHashMap<>();
    private final IdentityHashMap<Object, Object> parents = new IdentityHashMap<>();
    private final Set<Object> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private final JsonArray bones = new JsonArray();
    private int width, height, cubeCount;

    static JsonObject read(Class<?> type, String id) throws Exception {
        // This supported contract is deliberately explicit. Do not guess renderer/context arguments.
        var allParts = type.getMethod("getAllParts");
        Object model = type.getConstructor().newInstance();
        EntityGeometry reader = new EntityGeometry();
        List<Object> parts = list(allParts.invoke(model), MAX_PARTS);
        require(!parts.isEmpty(), "Model has no parts");
        for (Object part : parts) {
            require(reader.names.put(part, "bone_" + reader.names.size()) == null, "Duplicate part identity");
        }
        for (Object part : parts) {
            for (Object child : list(field(part, "childModels"), MAX_PARTS)) {
                require(reader.names.containsKey(child), "Child absent from getAllParts");
                require(!reader.parents.containsKey(child), "Part has multiple parents");
                reader.parents.put(child, part);
            }
        }
        for (Object part : parts) {
            if (!reader.parents.containsKey(part)) reader.visit(part, new double[3], 0);
        }
        require(reader.visited.size() == parts.size(), "Cyclic or unreachable part hierarchy");
        require(reader.cubeCount > 0, "Model has no cubes");
        JsonObject description = new JsonObject();
        description.addProperty("identifier", id);
        description.addProperty("texture_width", reader.width);
        description.addProperty("texture_height", reader.height);
        JsonObject modelJson = new JsonObject();
        modelJson.add("description", description);
        modelJson.add("bones", reader.bones);
        JsonArray geometries = new JsonArray();
        geometries.add(modelJson);
        JsonObject result = new JsonObject();
        result.addProperty("format_version", "1.16.0");
        result.add("minecraft:geometry", geometries);
        validate(result);
        return result;
    }

    private void visit(Object part, double[] parentPivot, int depth) throws Exception {
        require(depth < MAX_DEPTH, "Bone depth limit exceeded");
        require(visited.add(part), "Cyclic part hierarchy");
        double[] scale = {number(field(part, "scaleX")), number(field(part, "scaleY")), number(field(part, "scaleZ"))};
        boolean scaled = scale[0] != 1 || scale[1] != 1 || scale[2] != 1;
        require(!scaled || !Boolean.TRUE.equals(field(part, "scaleChildren")), "Unsupported inherited part scale");
        for (double value : scale) require(value > 0, "Unsupported non-positive part scale");
        for (String axis : List.of("X", "Y", "Z")) {
            require(number(field(part, "offset" + axis)) == 0, "Unsupported part render offset");
        }
        require(Boolean.TRUE.equals(field(part, "showModel")), "Unsupported hidden part");
        int tw = textureDimension(field(part, "textureWidth"));
        int th = textureDimension(field(part, "textureHeight"));
        if (width == 0) { width = tw; height = th; }
        require(width == tw && height == th, "Mixed part texture dimensions");
        double[] pivot = new double[3];
        String[] axes = {"X", "Y", "Z"};
        double[] rotation = new double[3];
        for (int i = 0; i < 3; i++) {
            pivot[i] = parentPivot[i] + number(field(part, "rotationPoint" + axes[i]));
            rotation[i] = Math.toDegrees(number(field(part, "rotateAngle" + axes[i])));
        }
        JsonObject bone = new JsonObject();
        bone.addProperty("name", names.get(part));
        if (parents.containsKey(part)) bone.addProperty("parent", names.get(parents.get(part)));
        // Java -> editor -> Bedrock cancels the two X/rotation flips; only Y changes sign.
        // See docs/entity-static-geometry.md for pinned codec references and the coordinate contract.
        bone.add("pivot", vector(pivot[0], 24 - pivot[1], pivot[2]));
        bone.add("rotation", vector(rotation));
        JsonArray cubes = new JsonArray();
        for (Object cube : list(field(part, "cubeList"), MAX_CUBES)) {
            require(++cubeCount <= MAX_CUBES, "Cube count limit exceeded");
            cubes.add(cube(cube, pivot, scale, width, height));
        }
        if (!cubes.isEmpty()) bone.add("cubes", cubes);
        bones.add(bone);
        for (Object child : list(field(part, "childModels"), MAX_PARTS)) visit(child, pivot, depth + 1);
    }

    /** Read each cube's actual vertex positions and UVs (including mirror and inflation). */
    static JsonObject cube(Object cube, double[] pivot, double[] scale, int width, int height) throws Exception {
        Object quads = field(cube, "quads");
        require(Array.getLength(quads) == 6, "Unsupported cube face count");
        double[] min = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        double[] max = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        JsonObject uv = new JsonObject();
        for (int i = 0; i < 6; i++) {
            Object quad = Array.get(quads, i);
            require(quad != null, "Null cube face");
            Object vertices = field(quad, "vertexPositions");
            require(Array.getLength(vertices) == 4, "Non-quad cube face");
            double[][] points = new double[4][5];
            for (int j = 0; j < 4; j++) {
                Object vertex = Array.get(vertices, j);
                Object position = field(vertex, "position");
                double[] p = points[j];
                p[0] = number(field(position, "x")) * scale[0];
                p[1] = -number(field(position, "y")) * scale[1];
                p[2] = number(field(position, "z")) * scale[2];
                p[3] = number(field(vertex, "textureU")) * width;
                p[4] = number(field(vertex, "textureV")) * height;
                for (int k = 0; k < 3; k++) { min[k] = Math.min(min[k], p[k]); max[k] = Math.max(max[k], p[k]); }
            }
            Object normal = field(quad, "normal");
            double nx = -number(field(normal, "x")), ny = -number(field(normal, "y")), nz = number(field(normal, "z"));
            require(Math.abs(nx) + Math.abs(ny) + Math.abs(nz) == 1, "Non-axis-aligned face normal");
            String face = nx == 1 ? "east" : nx == -1 ? "west" : ny == 1 ? "up" : ny == -1 ? "down" : nz == 1 ? "south" : "north";
            JsonObject mapping = faceUv(points, face);
            if (mapping != null) {
                require(!uv.has(face), "Duplicate cube face normal");
                uv.add(face, mapping);
            }
        }
        require(!uv.isEmpty(), "Cube has no drawable faces");
        JsonObject result = new JsonObject();
        result.add("origin", vector(min[0] + pivot[0], min[1] + 24 - pivot[1], min[2] + pivot[2]));
        // A plane retains exactly zero on its collapsed axis. There is no 30px/block overlap check.
        result.add("size", vector(max[0] - min[0], max[1] - min[1], max[2] - min[2]));
        result.add("uv", uv);
        return result;
    }

    private static JsonObject faceUv(double[][] points, String face) {
        double[][] projected = new double[4][2];
        double left = Double.POSITIVE_INFINITY, right = Double.NEGATIVE_INFINITY;
        double top = Double.POSITIVE_INFINITY, bottom = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 4; i++) {
            double[] p = points[i];
            double u = switch (face) {
                case "south" -> -p[0];
                case "north", "up", "down" -> p[0];
                case "east" -> -p[2];
                default -> p[2];
            };
            double v = switch (face) { case "up" -> -p[2]; case "down" -> p[2]; default -> -p[1]; };
            projected[i] = new double[]{u, v};
            left = Math.min(left, u); right = Math.max(right, u);
            top = Math.min(top, v); bottom = Math.max(bottom, v);
        }
        if (left == right || top == bottom) return null; // Only the collapsed side faces, not the plane itself.
        double[] start = null, end = null;
        for (int i = 0; i < 4; i++) {
            if (projected[i][0] == left && projected[i][1] == top) start = points[i];
            if (projected[i][0] == right && projected[i][1] == bottom) end = points[i];
        }
        require(start != null && end != null, "Non-rectangular cube face");
        for (int i = 0; i < 4; i++) {
            require(close(points[i][3], projected[i][0] == left ? start[3] : end[3])
                    && close(points[i][4], projected[i][1] == top ? start[4] : end[4]), "Unsupported rotated/skewed face UV");
        }
        JsonObject result = new JsonObject();
        result.add("uv", vector(start[3], start[4]));
        result.add("uv_size", vector(end[3] - start[3], end[4] - start[4]));
        return result;
    }

    /** Validation of the emitted cube-only 1.16 subset, not a validator for arbitrary Bedrock assets. */
    static void validate(JsonObject json) {
        require(json.get("format_version").getAsString().equals("1.16.0"), "Unexpected geometry version");
        JsonArray geometries = json.getAsJsonArray("minecraft:geometry");
        require(geometries.size() == 1, "Expected one geometry");
        JsonObject geometry = geometries.get(0).getAsJsonObject();
        JsonObject description = geometry.getAsJsonObject("description");
        require(description.get("identifier").getAsString().matches("geometry\\.[a-z0-9_.]+"), "Invalid geometry id");
        textureDimension(description.get("texture_width").getAsDouble());
        textureDimension(description.get("texture_height").getAsDouble());
        JsonArray bones = geometry.getAsJsonArray("bones");
        require(!bones.isEmpty() && bones.size() <= MAX_PARTS, "Invalid bone count");
        Map<String, String> parents = new HashMap<>();
        int cubes = 0;
        for (JsonElement element : bones) {
            JsonObject bone = element.getAsJsonObject();
            String name = bone.get("name").getAsString();
            require(name.matches("bone_[0-9]+") && !parents.containsKey(name), "Invalid/duplicate bone id");
            parents.put(name, bone.has("parent") ? bone.get("parent").getAsString() : null);
            validateVector(bone.getAsJsonArray("pivot"), 3);
            validateVector(bone.getAsJsonArray("rotation"), 3);
            if (!bone.has("cubes")) continue;
            for (JsonElement c : bone.getAsJsonArray("cubes")) {
                require(++cubes <= MAX_CUBES, "Cube count limit exceeded");
                JsonObject cube = c.getAsJsonObject();
                validateVector(cube.getAsJsonArray("origin"), 3);
                validateVector(cube.getAsJsonArray("size"), 3);
                for (JsonElement n : cube.getAsJsonArray("size")) require(n.getAsDouble() >= 0, "Negative cube size");
                JsonObject uv = cube.getAsJsonObject("uv");
                require(!uv.isEmpty(), "Empty cube UV");
                for (var face : uv.entrySet()) {
                    require(Set.of("north", "south", "east", "west", "up", "down").contains(face.getKey()), "Unknown face");
                    validateVector(face.getValue().getAsJsonObject().getAsJsonArray("uv"), 2);
                    validateVector(face.getValue().getAsJsonObject().getAsJsonArray("uv_size"), 2);
                }
            }
        }
        require(cubes > 0, "Empty geometry");
        for (String name : parents.keySet()) {
            Set<String> chain = new HashSet<>();
            for (String current = name; current != null; current = parents.get(current)) {
                require(parents.containsKey(current), "Unresolved parent");
                require(chain.add(current) && chain.size() <= MAX_DEPTH, "Cyclic/deep bone hierarchy");
            }
        }
    }

    private static void validateVector(JsonArray array, int length) {
        require(array.size() == length, "Invalid vector length");
        for (JsonElement value : array) number(value.getAsDouble());
    }

    static Object field(Object object, String name) throws Exception {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try { var field = type.getDeclaredField(name); field.setAccessible(true); return field.get(object); }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(object.getClass().getName() + "." + name);
    }

    private static List<Object> list(Object value, int limit) {
        require(value instanceof Iterable<?>, "Expected iterable model data");
        List<Object> result = new ArrayList<>();
        for (Object entry : (Iterable<?>) value) {
            require(entry != null && result.size() < limit, "Null entry or model data limit exceeded");
            result.add(entry);
        }
        return result;
    }

    private static int textureDimension(Object value) {
        double n = number(value);
        require(n > 0 && n <= 16384 && n == Math.rint(n), "Invalid texture dimension");
        return (int) n;
    }

    private static double number(Object value) {
        double n = ((Number) value).doubleValue();
        require(Double.isFinite(n) && Math.abs(n) <= 1_000_000, "Non-finite or excessive numeric value");
        return n;
    }

    private static JsonArray vector(double... values) {
        JsonArray array = new JsonArray();
        for (double value : values) { number(value); array.add(value == 0 ? 0d : value); }
        return array;
    }

    private static boolean close(double a, double b) { return Math.abs(a - b) < 0.0001; }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
