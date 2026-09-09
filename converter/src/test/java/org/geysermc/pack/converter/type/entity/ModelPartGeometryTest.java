package org.geysermc.pack.converter.type.entity;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ModelPartGeometryTest {
    public static class Material { public int xTexSize = 128, yTexSize = 64; }
    public record Vertex(float x, float y, float z, float u, float v) { }
    public static class Normal { public float x, y, z = -1; }
    public static class Polygon {
        public Vertex[] vertices = {new Vertex(0,0,0,0,0), new Vertex(40,0,0,.25f,0),
                new Vertex(40,4,0,.25f,.5f), new Vertex(0,4,0,0,.5f)};
        public Normal normal = new Normal();
    }
    public static class Cube { public Polygon[] polygons = {new Polygon()}; }
    public static class Part {
        public float x, y, z, xRot, yRot, zRot, xScale = 1, yScale = 1, zScale = 1;
        public boolean visible = true, skipDraw;
        public List<Cube> cubes = List.of();
        public Map<String, Part> children = new LinkedHashMap<>();
    }
    public static class Layer {
        public Material material = new Material();
        public Part root = new Part();
        public Layer() {
            Part child = new Part(); child.x = 3; child.y = 14; child.z = -3;
            child.xRot = -.7853982f; child.cubes = List.of(new Cube());
            root.children.put("../unsafe", child);
        }
        public Part bakeRoot() { return root; }
    }
    public static class Factory {
        public static Layer createBodyLayer() {
            if (Runtime.getRuntime().maxMemory() > 256L * 1024 * 1024) throw new AssertionError("Worker heap exceeds 256 MiB");
            return new Layer();
        }
    }
    public static class Hang { public static Layer createBodyLayer() { while (true) Thread.onSpinWait(); } }
    public static class Flood {
        public static Layer createBodyLayer() throws Exception {
            var output = new java.io.FileOutputStream(java.io.FileDescriptor.out);
            byte[] buffer = new byte[8192]; while (true) output.write(buffer);
        }
    }
    public static class Hook {
        public static Layer createBodyLayer() {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> { while (true) Thread.onSpinWait(); }));
            return Factory.createBodyLayer();
        }
    }

    @Test void modelPartOffsetsAndPlanePixelsArePreserved() throws Exception {
        var json = ModelPartGeometry.readLayer(new Layer(), "geometry.test.model");
        var bone = json.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject().getAsJsonArray("bones").get(1).getAsJsonObject();
        assertEquals(3, bone.getAsJsonArray("pivot").get(0).getAsInt());
        assertEquals(10, bone.getAsJsonArray("pivot").get(1).getAsInt());
        assertEquals(Math.toDegrees(-.7853982f), bone.getAsJsonArray("rotation").get(0).getAsDouble());
        var cube = bone.getAsJsonArray("cubes").get(0).getAsJsonObject();
        assertEquals(40, cube.getAsJsonArray("size").get(0).getAsInt());
        assertEquals(0, cube.getAsJsonArray("size").get(2).getAsInt());
        assertEquals(32, cube.getAsJsonObject("uv").getAsJsonObject("north").getAsJsonArray("uv_size").get(0).getAsInt());
        assertFalse(json.toString().contains("unsafe"));
    }

    @Test void rejectsScaleVisibilityCyclesAndSkewedUvsWithoutDroppingData() {
        for (int mode = 0; mode < 6; mode++) {
            Layer layer = new Layer(); Part child = layer.root.children.values().iterator().next();
            switch (mode) {
                case 0 -> child.xScale = -1;
                case 1 -> child.xScale = 2;
                case 2 -> child.skipDraw = true;
                case 3 -> child.children.put("cycle", layer.root);
                case 4 -> child.cubes.get(0).polygons[0].vertices[2] = new Vertex(40,4,0,.33f,.5f);
                case 5 -> child.cubes.get(0).polygons[0].vertices[1] = child.cubes.get(0).polygons[0].vertices[0];
            }
            assertThrows(IllegalArgumentException.class, () -> ModelPartGeometry.readLayer(layer, "geometry.test.model"));
        }
    }

    @Test void layerWorkerHasHeapCapAndHaltsDespiteShutdownHook() throws Exception {
        var input = EntityGeometryTest.input(Hook.class, Duration.ofSeconds(10));
        assertNotNull(new EntityConverter().convertLayer(input));
    }

    @Test void hungLayerFactoryIsKilledByDeadline() {
        var input = EntityGeometryTest.input(Hang.class, Duration.ofMillis(1500));
        IOException failure = assertThrows(IOException.class, () -> new EntityConverter().convertLayer(input));
        assertTrue(failure.getMessage().contains("timed out"), failure.toString());
        long pid = Long.parseLong(failure.getMessage().replaceAll(".*pid=([0-9]+).*", "$1"));
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }

    @Test void layerFactoryCannotExceedResponseCap() {
        var input = EntityGeometryTest.input(Flood.class, Duration.ofSeconds(10));
        IOException failure = assertThrows(IOException.class, () -> new EntityConverter().convertLayer(input));
        assertTrue(failure.getMessage().contains("output exceeded 8388608"), failure.toString());
    }
}
