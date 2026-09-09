package org.geysermc.pack.converter.type.entity;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class EntityGeometryTest {
    @TempDir Path directory;

    public static class Vec {
        public float x, y, z;
        Vec(float x, float y, float z) { this.x=x; this.y=y; this.z=z; }
    }
    public static class Vertex {
        public Vec position;
        public float textureU, textureV;
        Vertex(float x, float y, float z, float u, float v) {
            position = new Vec(x,y,z); textureU=u/64; textureV=v/64;
        }
    }
    public static class Quad {
        public Vertex[] vertexPositions;
        public Vec normal;
        Quad(Vec normal, Vertex... vertices) { this.normal=normal; vertexPositions=vertices; }
    }
    public static class Box {
        public Quad[] quads;
        // Real-shaped rectangular quads, with a distinct UV island on every cube.
        Box(float offset, float depth) {
            quads = new Quad[]{
                face(new Vec(0,0,-1), offset, new float[][]{{0,0,0},{40,0,0},{40,4,0},{0,4,0}}),
                face(new Vec(0,0,1), offset+8, new float[][]{{40,0,depth},{0,0,depth},{0,4,depth},{40,4,depth}}),
                face(new Vec(-1,0,0), offset, new float[][]{{0,0,depth},{0,0,0},{0,4,0},{0,4,depth}}),
                face(new Vec(1,0,0), offset, new float[][]{{40,0,0},{40,0,depth},{40,4,depth},{40,4,0}}),
                face(new Vec(0,-1,0), offset, new float[][]{{40,0,0},{0,0,0},{0,0,depth},{40,0,depth}}),
                face(new Vec(0,1,0), offset, new float[][]{{0,4,depth},{40,4,depth},{40,4,0},{0,4,0}})
            };
        }
        static Quad face(Vec normal, float uv, float[][] p) {
            return new Quad(normal, new Vertex(p[0][0],p[0][1],p[0][2],uv,0),
                    new Vertex(p[1][0],p[1][1],p[1][2],uv+4,0),
                    new Vertex(p[2][0],p[2][1],p[2][2],uv+4,4),
                    new Vertex(p[3][0],p[3][1],p[3][2],uv,4));
        }
    }
    public static class Part {
        public float textureWidth=64, textureHeight=64;
        public float scaleX=1, scaleY=1, scaleZ=1, offsetX, offsetY, offsetZ;
        public float rotationPointX, rotationPointY, rotationPointZ, rotateAngleX, rotateAngleY, rotateAngleZ;
        public boolean showModel=true;
        public boolean scaleChildren;
        public String boxName="../unsafe duplicate name";
        public List<Part> childModels=new ArrayList<>();
        public List<Box> cubeList=new ArrayList<>();
    }
    public static class Model {
        public Part root=new Part(), child=new Part();
        public Model() {
            root.rotationPointY=24; child.rotationPointY=-9.3f; child.rotationPointZ=-2;
            root.childModels.add(child);
            child.cubeList.add(new Box(0,0)); child.cubeList.add(new Box(16,2));
        }
        public Iterable<Part> getAllParts() { return List.of(root, child); }
    }
    public static class Cycle extends Model { public Cycle() { child.childModels.add(root); } }
    public static class MissingChild extends Model { public MissingChild() { child.childModels.add(new Part()); } }
    public static class SharedChild extends Model { public SharedChild() { root.childModels.add(child); } }
    public static class NonFinite extends Model { public NonFinite() { child.rotationPointX=Float.NaN; } }
    public static class Scaled extends Model { public Scaled() { child.scaleX=2; child.scaleChildren=true; } }
    public static class LocalScale extends Model { public LocalScale() { child.scaleX=1.1f; } }
    public static class Asymmetric extends Model {
        public Asymmetric() { child.rotationPointX=3; child.rotateAngleX=0.2f; child.rotateAngleY=-0.3f; child.rotateAngleZ=0.4f; }
    }
    public static class Hang extends Model { public Hang() { while (true) Thread.onSpinWait(); } }
    public static class Exit extends Model { public Exit() { Runtime.getRuntime().halt(7); } }
    public static class Flood extends Model {
        public Flood() throws Exception {
            var stream=new java.io.FileOutputStream(java.io.FileDescriptor.out);
            byte[] block=new byte[8192];
            while(true) stream.write(block);
        }
    }
    public static class TooDeep extends Model {
        public final List<Part> parts=new ArrayList<>();
        public TooDeep() {
            root.childModels.clear(); parts.add(root); Part parent=root;
            for(int i=0;i<128;i++) { Part child=new Part(); parent.childModels.add(child); parts.add(child); parent=child; }
        }
        @Override public Iterable<Part> getAllParts() { return parts; }
    }

    @Test void preservesCubeUvsPlanesLargeDimensionsAndSafeHierarchy() throws Exception {
        var json=EntityGeometry.read(Model.class,"geometry.test.model");
        var bones=json.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject().getAsJsonArray("bones");
        assertEquals("bone_0",bones.get(1).getAsJsonObject().get("parent").getAsString());
        assertEquals(9.3,bones.get(1).getAsJsonObject().getAsJsonArray("pivot").get(1).getAsDouble(),0.00001);
        var cubes=bones.get(1).getAsJsonObject().getAsJsonArray("cubes");
        assertEquals(2,cubes.size());
        assertEquals(40,cubes.get(0).getAsJsonObject().getAsJsonArray("size").get(0).getAsInt());
        assertEquals(0,cubes.get(0).getAsJsonObject().getAsJsonArray("size").get(2).getAsInt());
        assertEquals(2,cubes.get(0).getAsJsonObject().getAsJsonObject("uv").size());
        assertEquals(0,uv(cubes.get(0).getAsJsonObject(),"north"));
        assertEquals(16,uv(cubes.get(1).getAsJsonObject(),"north"));
        assertFalse(json.toString().contains("unsafe"));
    }
    private static int uv(JsonObject cube,String face) { return cube.getAsJsonObject("uv").getAsJsonObject(face).getAsJsonArray("uv").get(0).getAsInt(); }

    @Test void bakesOnlyLocalScaleWithoutChangingUv() throws Exception {
        var json=EntityGeometry.read(LocalScale.class,"geometry.test.model");
        var cube=json.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject().getAsJsonArray("bones")
                .get(1).getAsJsonObject().getAsJsonArray("cubes").get(0).getAsJsonObject();
        assertEquals(44,cube.getAsJsonArray("size").get(0).getAsDouble(),0.00001);
        assertEquals(0,uv(cube,"north"));
    }

    @Test void coordinateContractMatchesJavaAndBedrockCodecComposition() throws Exception {
        var json=EntityGeometry.read(Asymmetric.class,"geometry.test.model");
        var bone=json.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject().getAsJsonArray("bones").get(1).getAsJsonObject();
        assertEquals(3,bone.getAsJsonArray("pivot").get(0).getAsInt());
        assertEquals(3,bone.getAsJsonArray("cubes").get(0).getAsJsonObject().getAsJsonArray("origin").get(0).getAsInt());
        assertEquals(Math.toDegrees(0.2f),bone.getAsJsonArray("rotation").get(0).getAsDouble());
        assertEquals(Math.toDegrees(-0.3f),bone.getAsJsonArray("rotation").get(1).getAsDouble());
        assertEquals(Math.toDegrees(0.4f),bone.getAsJsonArray("rotation").get(2).getAsDouble());
    }

    @Test void rejectsBrokenTreesAndUnsupportedTransforms() {
        for (Class<?> type:List.of(Cycle.class,MissingChild.class,SharedChild.class,NonFinite.class,Scaled.class,TooDeep.class)) {
            assertThrows(IllegalArgumentException.class,()->EntityGeometry.read(type,"geometry.test.model"),type.getName());
        }
    }

    @Test void rejectsUnresolvedAndCyclicOutputParents() throws Exception {
        var json=EntityGeometry.read(Model.class,"geometry.test.model");
        var root=json.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject().getAsJsonArray("bones").get(0).getAsJsonObject();
        root.addProperty("parent","bone_999");
        assertThrows(IllegalArgumentException.class,()->EntityGeometry.validate(json));
        root.addProperty("parent","bone_1");
        assertThrows(IllegalArgumentException.class,()->EntityGeometry.validate(json));
    }

    @Test void realProcessMatchesDirectReadAndPublishesAtomically() throws Exception {
        var input=input(Model.class,Duration.ofSeconds(10));
        Path target=new EntityConverter().convertTo(input,directory,"nested/model.geo.json");
        assertEquals(EntityGeometry.read(Model.class,"geometry.test.model").toString(),Files.readString(target));
        assertThrows(IOException.class,()->new EntityConverter().convertTo(input,directory,"../outside"));
    }

    @Test void killsNonCooperativeConstructorAndPreservesExistingFile() throws Exception {
        Path target=directory.resolve("model.geo.json"); Files.writeString(target,"old validated geometry");
        long start=System.nanoTime();
        IOException error=assertThrows(IOException.class,()->new EntityConverter().convertTo(input(Hang.class,Duration.ofMillis(1500)),directory,"model.geo.json"));
        assertTrue(error.getMessage().contains("timed out"),error.toString());
        long pid=Long.parseLong(error.getMessage().replaceAll(".*pid=([0-9]+).*","$1"));
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),"Timed-out OS process must be dead");
        assertTrue(Duration.ofNanos(System.nanoTime()-start).toSeconds()<8);
        assertEquals("old validated geometry",Files.readString(target));
        try(var paths=Files.list(directory)) { assertEquals(1,paths.count()); }
        // A failed model cannot poison a subsequent conversion in this JVM.
        assertNotNull(new EntityConverter().convert(input(Model.class,Duration.ofSeconds(10))));
    }

    @Test void abruptWorkerExitIsAnExplicitFailure() {
        IOException error=assertThrows(IOException.class,()->new EntityConverter().convert(input(Exit.class,Duration.ofSeconds(10))));
        assertTrue(error.getMessage().contains("worker failed"));
    }

    @Test void excessiveWorkerOutputIsKilled() {
        IOException error=assertThrows(IOException.class,()->new EntityConverter().convert(input(Flood.class,Duration.ofSeconds(10))));
        assertTrue(error.getMessage().contains("output exceeded"),error.toString());
    }

    @Test void preservesMirroredPerFaceUv() throws Exception {
        Box box=new Box(0,2);
        for(Quad quad:box.quads) for(Vertex v:quad.vertexPositions) v.textureU=1-v.textureU;
        var cube=EntityGeometry.cube(box,new double[3],new double[]{1,1,1},64,64);
        var north=cube.getAsJsonObject("uv").getAsJsonObject("north");
        assertEquals(64,north.getAsJsonArray("uv").get(0).getAsInt());
        assertEquals(-4,north.getAsJsonArray("uv_size").get(0).getAsInt());
    }

    @Test void goodModelFinishesWhileAnotherWorkerIsHung() throws Exception {
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var hung=executor.submit(()->assertThrows(IOException.class,
                    ()->new EntityConverter().convert(input(Hang.class,Duration.ofSeconds(8)))));
            var good=executor.submit(()->new EntityConverter().convert(input(Model.class,Duration.ofSeconds(6))));
            assertNotNull(good.get(7,java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(hung.get(12,java.util.concurrent.TimeUnit.SECONDS).getMessage().contains("timed out"));
        }
    }

    @Test void reviewedRealGeometryGoldenPassesEmittedSchemaSubset() throws Exception {
        try(var resource=getClass().getResourceAsStream("/entity/bald-eagle.geo.json")) {
            assertNotNull(resource);
            EntityGeometry.validate(com.google.gson.JsonParser.parseString(new String(resource.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject());
        }
    }

    static EntityConverter.Input input(Class<?> model,Duration timeout) {
        List<Path> cp=Arrays.stream(System.getProperty("entity.worker.classpath").split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))).map(Path::of).toList();
        return new EntityConverter.Input(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java"),cp,cp,model.getName(),"geometry.test.model",timeout);
    }
}
