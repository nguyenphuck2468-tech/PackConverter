package org.geysermc.pack.converter.type.model;

import net.kyori.adventure.key.Key;
import org.geysermc.pack.converter.pipeline.ConversionContext;
import org.geysermc.pack.converter.util.DefaultLogListener;
import org.junit.jupiter.api.Test;
import team.unnamed.creative.model.Element;
import team.unnamed.creative.model.Model;
import team.unnamed.creative.model.ElementFace;
import team.unnamed.creative.base.CubeFace;
import team.unnamed.creative.base.Vector4Float;
import com.google.gson.JsonParser;
import org.geysermc.pack.bedrock.resource.BedrockResourcePack;
import org.geysermc.pack.bedrock.resource.Manifest;
import org.geysermc.pack.bedrock.resource.models.entity.modelentity.geometry.Bones;
import org.geysermc.pack.bedrock.resource.models.entity.modelentity.geometry.bones.Cubes;
import org.geysermc.pack.converter.pipeline.CombineContext;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BlockGeometryContainmentTest {
    // Cause-level reproducer for Hydraulic #11/#12. The original mod JAR/world
    // was not supplied in the issue; this is deliberately not a claimed Ad Astra fixture.
    @Test
    void oversizedBlockCannotPublishChunkPoisoningGeometry() throws Exception {
        Model source = Model.model().key(Key.key("regression:block/oversized"))
                .elements(element().from(-16, 0, 0).to(32, 16, 16).build()).build();
        BedrockModel converted = ModelConverter.INSTANCE.convert(source,
                new ConversionContext("regression", new DefaultLogListener()));
        var geometry = converted.model().geometry().getFirst();
        assertEquals("geometry.regression.oversized", geometry.description().identifier());
        assertEquals("oversized.json", converted.fileName());
        var cube = geometry.bones().getFirst().cubes().getFirst();
        assertArrayEquals(new float[] {-8, 0, -8}, cube.origin());
        assertArrayEquals(new float[] {16, 16, 16}, cube.size());
    }

    @Test
    void normalBlockGeometryIsPreserved() throws Exception {
        Model source = Model.model().key(Key.key("regression:block/slab"))
                .elements(element().from(0, 0, 0).to(16, 8, 16).build()).build();
        var converted = ModelConverter.INSTANCE.convert(source,
                new ConversionContext("regression", new DefaultLogListener()));
        var cube = converted.model().geometry().getFirst().bones().getFirst().cubes().getFirst();
        assertArrayEquals(new float[] {-8, 0, -8}, cube.origin());
        assertArrayEquals(new float[] {16, 8, 16}, cube.size());
    }

    @Test
    void namespaceCannotMisclassifyBlocksAsMobs() throws Exception {
        var source = Model.model().key(Key.key("entitymod:block/oversized"))
                .elements(element().from(-16, 0, 0).to(32, 16, 16).build()).build();
        var result = ModelConverter.INSTANCE.convert(source, new ConversionContext("test", new DefaultLogListener()));
        assertEquals(BedrockModel.ModelType.BLOCK, result.type());
        assertNull(BlockModelBounds.failure(result.model().geometry().getFirst().bones()));
    }

    @Test
    void boundsIncludeRotationAndAggregateExtents() {
        var left = cube(new float[] {-16, 0, -8}, new float[] {16, 16, 16});
        var right = cube(new float[] {0, 0, -8}, new float[] {16, 16, 16});
        assertNull(BlockModelBounds.failure(List.of(left)));
        assertNull(BlockModelBounds.failure(List.of(right)));
        assertNotNull(BlockModelBounds.failure(List.of(left, right)));

        var rotated = cube(new float[] {-15, 0, -15}, new float[] {30, 1, 30});
        assertNull(BlockModelBounds.failure(List.of(rotated)));
        rotated.cubes().getFirst().pivot(new float[] {0, 0, 0});
        rotated.cubes().getFirst().rotation(new float[] {0, 45, 0});
        assertNotNull(BlockModelBounds.failure(List.of(rotated)));
    }

    @Test
    void absoluteBoundsAndInvalidNumbersAreRejected() {
        assertNotNull(BlockModelBounds.failure(List.of(cube(new float[] {23, 0, 0}, new float[] {1, 1, 1}))));
        assertNotNull(BlockModelBounds.failure(List.of(cube(new float[] {0, 31, 0}, new float[] {1, 1, 1}))));
        assertNotNull(BlockModelBounds.failure(List.of(cube(new float[] {Float.NaN, 0, 0}, new float[] {1, 1, 1}))));
        assertNotNull(BlockModelBounds.failure(List.of(cube(new float[] {0, 0, 0}, new float[] {-1, 1, 1}))));
    }

    @Test
    void replacementIsExplicitAndGoldenAndDoesNotRemoveHealthySibling(@TempDir Path directory) throws Exception {
        List<String> warnings = new ArrayList<>();
        var logger = new DefaultLogListener() {
            @Override public void warn(String message) { warnings.add(message); }
        };
        var context = new ConversionContext("test", logger);
        var invalid = Model.model().key(Key.key("regression:block/oversized"))
                .elements(element().from(-16, 0, 0).to(32, 16, 16).build()).build();
        var valid = Model.model().key(Key.key("regression:block/slab"))
                .elements(element().from(0, 0, 0).to(16, 8, 16).build()).build();
        var replaced = ModelConverter.INSTANCE.convert(invalid, context);
        var healthy = ModelConverter.INSTANCE.convert(valid, context);
        var pack = new BedrockResourcePack(directory);
        ModelConverter.INSTANCE.include(pack, List.of(replaced, healthy), new CombineContext(null, logger));
        assertEquals(2, pack.blockModels().size());
        assertSame(healthy.model(), pack.blockModels().get("models/blocks/slab.json"));
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("regression:block/oversized geometry FAILED(block bounds exceeded"));
        // Exercise the real geometry serializer, which omits empty schema maps/lists.
        pack.manifest(new Manifest());
        pack.export();
        try (var reader = new InputStreamReader(getClass().getResourceAsStream("/golden/oversized-block.json"), StandardCharsets.UTF_8)) {
            assertEquals(JsonParser.parseReader(reader), JsonParser.parseString(
                    Files.readString(directory.resolve("models/blocks/oversized.json"))));
        }
    }

    private static Bones cube(float[] origin, float[] size) {
        var cube = new Cubes();
        cube.origin(origin);
        cube.size(size);
        var bone = new Bones();
        bone.cubes(List.of(cube));
        return bone;
    }

    private static Element.Builder element() {
        return Element.element().face(CubeFace.NORTH, ElementFace.face()
                .texture("#surface").uv(new Vector4Float(0, 0, 16, 16)).build());
    }
}
