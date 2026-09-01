package org.geysermc.pack.converter.type.entity;

import org.geysermc.pack.bedrock.resource.models.entity.ModelEntity;
import org.geysermc.pack.bedrock.resource.models.entity.modelentity.Geometry;
import org.geysermc.pack.bedrock.resource.models.entity.modelentity.geometry.Bones;
import org.geysermc.pack.bedrock.resource.models.entity.modelentity.geometry.Description;
import org.geysermc.pack.bedrock.resource.models.entity.modelentity.geometry.bones.Cubes;
import org.geysermc.pack.converter.type.model.BedrockModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityGeometryValidatorTest {
    @Test
    void acceptsEmptyStructuralParentWithValidChildCube() {
        Bones root = bone("root", null, List.of());
        Bones child = bone("child", "root", List.of(cube()));
        assertNull(EntityGeometryValidator.invalidReason(model(root, child)));
    }

    @Test
    void rejectsMissingParentAndParentCycle() {
        String missing = EntityGeometryValidator.invalidReason(model(bone("child", "missing", List.of(cube()))));
        assertTrue(missing.contains("missing parent"));

        String cycle = EntityGeometryValidator.invalidReason(model(
                bone("first", "second", List.of(cube())), bone("second", "first", List.of())));
        assertTrue(cycle.contains("cycle"));
    }

    @Test
    void rejectsNonFiniteTransformsWithoutClampingValidData() {
        Cubes cube = cube();
        cube.origin(new float[]{Float.NaN, 0, 0});
        assertTrue(EntityGeometryValidator.invalidReason(model(bone("root", null, List.of(cube))))
                .contains("non-finite"));
    }

    private static BedrockModel model(Bones... bones) {
        Description description = new Description();
        description.identifier("geometry.example.test");
        description.textureWidth(64);
        description.textureHeight(64);
        Geometry geometry = new Geometry();
        geometry.description(description);
        geometry.bones(List.of(bones));
        ModelEntity entity = new ModelEntity();
        entity.geometry(List.of(geometry));
        return new BedrockModel(BedrockModel.ModelType.ENTITY, "example.test.json", entity);
    }

    private static Bones bone(String name, String parent, List<Cubes> cubes) {
        Bones bone = new Bones();
        bone.name(name);
        if (parent != null) bone.parent(parent);
        bone.pivot(new float[]{0, 0, 0});
        bone.rotation(new float[]{0, 0, 0});
        bone.cubes(cubes);
        return bone;
    }

    private static Cubes cube() {
        Cubes cube = new Cubes();
        cube.origin(new float[]{0, 0, 0});
        cube.size(new float[]{1, 1, 1});
        return cube;
    }
}
