package org.geysermc.pack.converter.type.entity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Array;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Static baked ModelPart reader. Independent of Citadel fields, constructors and cube encoding. */
final class ModelPartGeometry {
    private final Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    private final JsonArray bones = new JsonArray();
    private final int width, height;
    private int cubes;

    private ModelPartGeometry(int width, int height) { this.width = width; this.height = height; }

    static JsonObject read(Class<?> model, String identifier) throws Exception {
        java.lang.reflect.Method factory;
        Object[] arguments;
        try {
            factory = model.getMethod("createBodyLayer");
            arguments = new Object[0];
        } catch (NoSuchMethodException absent) {
            Class<?> deformation = Class.forName("net.minecraft.client.model.geom.builders.CubeDeformation", false, model.getClassLoader());
            factory = model.getMethod("createBodyLayer", deformation);
            // This is the actual MC zero-deformation constant, not guessed constructor/context data.
            arguments = new Object[]{deformation.getField("NONE").get(null)};
        }
        require(Modifier.isStatic(factory.getModifiers()), "createBodyLayer must be static");
        Object layer = factory.invoke(null, arguments);
        return readLayer(layer, identifier);
    }

    static JsonObject readLayer(Object layer, String identifier) throws Exception {
        Object material = field(layer, "material");
        int width = dimension(field(material, "xTexSize")), height = dimension(field(material, "yTexSize"));
        Object root = layer.getClass().getMethod("bakeRoot").invoke(layer);
        ModelPartGeometry reader = new ModelPartGeometry(width, height);
        reader.visit(root, null, new double[3], 0);
        JsonObject description = new JsonObject();
        description.addProperty("identifier", identifier);
        description.addProperty("texture_width", width);
        description.addProperty("texture_height", height);
        JsonObject geometry = new JsonObject();
        geometry.add("description", description);
        geometry.add("bones", reader.bones);
        JsonArray geometries = new JsonArray(); geometries.add(geometry);
        JsonObject result = new JsonObject();
        result.addProperty("format_version", "1.16.0"); result.add("minecraft:geometry", geometries);
        EntityGeometry.validate(result); // Output schema/graph only; never uses the Citadel reader.
        return result;
    }

    private void visit(Object part, String parent, double[] offset, int depth) throws Exception {
        require(depth < 128 && seen.size() < 1024, "ModelPart hierarchy limit exceeded");
        require(seen.add(part), "Cyclic/shared ModelPart identity");
        require(Boolean.TRUE.equals(field(part, "visible")) && Boolean.FALSE.equals(field(part, "skipDraw")),
                "Unsupported ModelPart visibility/skipDraw");
        // ModelPart scales its children too. Do not bake this as Citadel's local cube-only scale.
        for (String axis : List.of("x", "y", "z")) require(number(field(part, axis + "Scale")) == 1,
                "Unsupported ModelPart scale: " + axis);
        double[] pivot = {offset[0] + number(field(part, "x")), offset[1] + number(field(part, "y")), offset[2] + number(field(part, "z"))};
        String name = "bone_" + bones.size();
        JsonObject bone = new JsonObject(); bone.addProperty("name", name);
        if (parent != null) bone.addProperty("parent", parent);
        bone.add("pivot", vector(pivot[0], 24 - pivot[1], pivot[2]));
        bone.add("rotation", vector(Math.toDegrees(number(field(part, "xRot"))),
                Math.toDegrees(number(field(part, "yRot"))), Math.toDegrees(number(field(part, "zRot")))));
        JsonArray boxes = new JsonArray();
        for (Object cube : (List<?>) field(part, "cubes")) {
            require(++cubes <= 16384, "ModelPart cube limit exceeded");
            boxes.add(cube(cube, pivot));
        }
        if (!boxes.isEmpty()) bone.add("cubes", boxes);
        bones.add(bone);
        Map<?, ?> children = (Map<?, ?>) field(part, "children");
        require(children.size() <= 1024, "ModelPart child limit exceeded");
        require(children.keySet().stream().allMatch(k -> k instanceof String), "Invalid child key");
        for (String key : children.keySet().stream().map(String.class::cast).sorted().toList()) {
            visit(children.get(key), name, pivot, depth + 1);
        }
    }

    private JsonObject cube(Object cube, double[] pivot) throws Exception {
        Object polygons = field(cube, "polygons");
        int count = Array.getLength(polygons);
        require(count > 0 && count <= 6, "Unsupported ModelPart polygon count");
        double[] min = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        double[] max = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        double[][][] points = new double[count][4][5];
        String[] faces = new String[count];
        int[] axes = new int[count];
        boolean[] maximumSide = new boolean[count];
        for (int i = 0; i < count; i++) {
            Object polygon = Array.get(polygons, i);
            Object vertices = field(polygon, "vertices");
            require(Array.getLength(vertices) == 4, "Unsupported ModelPart non-quad polygon");
            Object normal = field(polygon, "normal");
            double nx = number(field(normal, "x")), ny = number(field(normal, "y")), nz = number(field(normal, "z"));
            require((Math.abs(nx) == 1 && ny == 0 && nz == 0) || (Math.abs(ny) == 1 && nx == 0 && nz == 0)
                    || (Math.abs(nz) == 1 && nx == 0 && ny == 0), "Non-axis ModelPart face normal");
            faces[i] = nx == -1 ? "east" : nx == 1 ? "west" : ny == -1 ? "up" : ny == 1 ? "down" : nz == -1 ? "north" : "south";
            axes[i] = nx != 0 ? 0 : ny != 0 ? 1 : 2;
            maximumSide[i] = nx != 0 ? nx > 0 : ny != 0 ? ny < 0 : nz > 0;
            for (int j = 0; j < 4; j++) {
                Object vertex = Array.get(vertices, j);
                double[] p = points[i][j];
                // 26.2 Vertex.x/y/z are pixels. worldX/Y/Z divide by 16 and must NOT be used.
                p[0] = number(field(vertex, "x")); p[1] = -number(field(vertex, "y")); p[2] = number(field(vertex, "z"));
                p[3] = number(field(vertex, "u")) * width; p[4] = number(field(vertex, "v")) * height;
                for (int k = 0; k < 3; k++) { min[k] = Math.min(min[k], p[k]); max[k] = Math.max(max[k], p[k]); }
            }
        }
        JsonObject uv = new JsonObject();
        for (int i = 0; i < count; i++) {
            int axis = axes[i];
            require(points[i][0][axis] == (maximumSide[i] ? max[axis] : min[axis]),
                    "ModelPart normal does not match cube boundary");
            for (double[] point : points[i]) {
                require(point[axis] == points[i][0][axis], "Non-planar ModelPart face");
                for (int k = 0; k < 3; k++) require(point[k] == min[k] || point[k] == max[k], "Non-box ModelPart vertices");
            }
            JsonObject mapping = faceUv(points[i], faces[i]);
            if (mapping != null) {
                require(!uv.has(faces[i]), "Duplicate ModelPart face"); uv.add(faces[i], mapping);
            }
        }
        require(!uv.isEmpty(), "ModelPart cube has no drawable faces");
        JsonObject result = new JsonObject();
        result.add("origin", vector(pivot[0] + min[0], 24 - pivot[1] + min[1], pivot[2] + min[2]));
        result.add("size", vector(max[0] - min[0], max[1] - min[1], max[2] - min[2]));
        result.add("uv", uv);
        return result;
    }

    /** Bedrock face-corner basis established independently against the pinned Java/Bedrock codecs. */
    private static JsonObject faceUv(double[][] points, String face) {
        double[] s = new double[4], t = new double[4];
        double left = Double.POSITIVE_INFINITY, right = Double.NEGATIVE_INFINITY, top = Double.POSITIVE_INFINITY, bottom = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 4; i++) {
            double[] p = points[i];
            s[i] = switch (face) { case "east" -> -p[2]; case "west" -> p[2]; case "south" -> -p[0]; default -> p[0]; };
            t[i] = switch (face) { case "up" -> -p[2]; case "down" -> p[2]; default -> -p[1]; };
            left = Math.min(left, s[i]); right = Math.max(right, s[i]); top = Math.min(top, t[i]); bottom = Math.max(bottom, t[i]);
        }
        if (left == right || top == bottom) return null;
        double[] start = null, end = null;
        for (int i = 0; i < 4; i++) {
            if (s[i] == left && t[i] == top) start = points[i];
            if (s[i] == right && t[i] == bottom) end = points[i];
        }
        require(start != null && end != null, "Non-rectangular ModelPart face");
        boolean[] corners = new boolean[4];
        for (int i = 0; i < 4; i++) {
            int corner = (s[i] == left ? 0 : 1) + (t[i] == top ? 0 : 2);
            require(!corners[corner], "Repeated ModelPart face corner"); corners[corner] = true;
            require(Math.abs(points[i][3] - (s[i] == left ? start[3] : end[3])) < 1e-5
                    && Math.abs(points[i][4] - (t[i] == top ? start[4] : end[4])) < 1e-5, "Unsupported rotated/skewed ModelPart UV");
        }
        JsonObject result = new JsonObject();
        result.add("uv", vector(start[3], start[4])); result.add("uv_size", vector(end[3] - start[3], end[4] - start[4]));
        return result;
    }

    private static Object field(Object object, String name) throws Exception {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try { var f = type.getDeclaredField(name); f.setAccessible(true); return f.get(object); }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(object.getClass().getName() + "." + name);
    }
    private static int dimension(Object value) {
        double n = number(value); require(n >= 1 && n <= 16384 && n == Math.rint(n), "Invalid layer texture dimension"); return (int) n;
    }
    private static double number(Object value) {
        double n = ((Number) value).doubleValue(); require(Double.isFinite(n) && Math.abs(n) <= 1_000_000, "Invalid ModelPart number"); return n;
    }
    private static JsonArray vector(double... values) {
        JsonArray result = new JsonArray(); for (double n : values) { number(n); result.add(n == 0 ? 0d : n); } return result;
    }
    private static void require(boolean valid, String reason) { if (!valid) throw new IllegalArgumentException(reason); }
}
