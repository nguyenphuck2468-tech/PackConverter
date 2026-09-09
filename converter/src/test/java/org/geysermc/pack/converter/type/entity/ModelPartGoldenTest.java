package org.geysermc.pack.converter.type.entity;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Independent oracle: factory bytecode constants -> editor coordinates -> Bedrock export.
 * Blockbench 47e633e4a1338f957ee7baa0acbcf54da11e77df, modded_entity.js 525-590,
 * bedrock.js 885-980, cube.js UVToLocal. Not a invocation of the full Blockbench app.
 * Uses original box UV offset/dimensions/mirror, never ModelPart polygon UV data or reader code.
 */
class ModelPartGoldenTest {
    @Test void reviewedGoldenMatchesIndependentFactoryComposition() throws Exception {
        JsonObject expected = compose();
        Path output = Path.of("build/entity-fixtures/mutant-creeper.oracle.geo.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(expected));
        assertEquals(resource("/entity/mutant-creeper.geo.json"), expected);
    }

    static JsonObject compose() throws Exception {
        List<JsonObject> parts = resource("/entity/mutant-creeper-factory.json").getAsJsonObject().getAsJsonArray("parts")
                .asList().stream().map(JsonElement::getAsJsonObject).toList();
        JsonArray bones = new JsonArray();
        JsonObject root = new JsonObject(); root.addProperty("name", "bone_0");
        root.add("pivot", vec(0,24,0)); root.add("rotation", vec(0,0,0)); bones.add(root);
        append(parts, "root", "bone_0", new double[]{0,24,0}, bones);
        JsonObject geometry = new JsonObject(), description = new JsonObject(), result = new JsonObject();
        description.addProperty("identifier", "geometry.fixture.mutant_creeper");
        description.addProperty("texture_width", 128); description.addProperty("texture_height", 64);
        geometry.add("description", description); geometry.add("bones", bones);
        JsonArray geometries = new JsonArray(); geometries.add(geometry);
        result.addProperty("format_version", "1.16.0"); result.add("minecraft:geometry", geometries);
        return result;
    }

    private static void append(List<JsonObject> parts, String parent, String parentId, double[] editorParent, JsonArray bones) {
        for (JsonObject p : parts.stream().filter(p -> p.get("parent").getAsString().equals(parent))
                .sorted(Comparator.comparing(p -> p.get("name").getAsString())).toList()) {
            String id = "bone_" + bones.size();
            double[] editor = {editorParent[0] - n(p,"offset",0), editorParent[1] - n(p,"offset",1), editorParent[2] + n(p,"offset",2)};
            double[] editorRotation = {-Math.toDegrees(n(p,"rotation",0)), -Math.toDegrees(n(p,"rotation",1)), Math.toDegrees(n(p,"rotation",2))};
            double w=n(p,"size",0), h=n(p,"size",1), d=n(p,"size",2);
            double editorFromX=editor[0] - n(p,"origin",0) - w;
            JsonObject bone = new JsonObject(), cube = new JsonObject();
            bone.addProperty("name",id); bone.addProperty("parent",parentId);
            bone.add("pivot",vec(-editor[0],editor[1],editor[2]));
            bone.add("rotation",vec(-editorRotation[0],-editorRotation[1],editorRotation[2]));
            cube.add("origin",vec(-(editorFromX+w),editor[1]-n(p,"origin",1)-h,editor[2]+n(p,"origin",2)));
            cube.add("size",vec(w,h,d));
            double u=n(p,"uv",0), v=n(p,"uv",1);
            JsonObject uv = new JsonObject();
            uv.add("east", face(u,v+d,d,h)); uv.add("north",face(u+d,v+d,w,h));
            uv.add("west", face(u+d+w,v+d,d,h)); uv.add("south",face(u+2*d+w,v+d,w,h));
            uv.add("up",face(u+d,v,w,d)); uv.add("down",face(u+d+w,v+d,w,-d));
            if (p.get("mirror").getAsBoolean()) {
                JsonElement east=uv.get("east"), west=uv.get("west"); uv.add("east",west); uv.add("west",east);
                for (var entry:uv.entrySet()) {
                    JsonObject face=entry.getValue().getAsJsonObject();
                    double start=face.getAsJsonArray("uv").get(0).getAsDouble(), size=face.getAsJsonArray("uv_size").get(0).getAsDouble();
                    face.getAsJsonArray("uv").set(0,new JsonPrimitive(start+size));
                    face.getAsJsonArray("uv_size").set(0,new JsonPrimitive(-size));
                }
            }
            cube.add("uv",uv); JsonArray cubes=new JsonArray(); cubes.add(cube); bone.add("cubes",cubes); bones.add(bone);
            append(parts,p.get("name").getAsString(),id,editor,bones);
        }
    }
    private static double n(JsonObject p,String key,int i) { return p.getAsJsonArray(key).get(i).getAsFloat(); }
    private static JsonObject face(double u,double v,double w,double h) {
        JsonObject f=new JsonObject(); f.add("uv",vec(u,v)); f.add("uv_size",vec(w,h)); return f;
    }
    private static JsonArray vec(double... values) { JsonArray a=new JsonArray(); for(double n:values)a.add(n==0?0d:n); return a; }
    static JsonElement resource(String name) throws Exception {
        try(var stream=ModelPartGoldenTest.class.getResourceAsStream(name)) {
            assertNotNull(stream,"Missing reviewed fixture: "+name);
            return JsonParser.parseString(new String(stream.readAllBytes(),StandardCharsets.UTF_8));
        }
    }
}
