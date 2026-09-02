/*
 * Copyright (c) 2019-2026 GeyserMC. http://geysermc.org
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

package org.geysermc.pack.converter.type.entity;

import org.geysermc.pack.bedrock.resource.models.entity.modelentity.Geometry;
import org.geysermc.pack.bedrock.resource.models.entity.modelentity.geometry.Bones;
import org.geysermc.pack.bedrock.resource.models.entity.modelentity.geometry.Description;
import org.geysermc.pack.bedrock.resource.models.entity.modelentity.geometry.bones.Cubes;
import org.geysermc.pack.converter.type.model.BedrockModel;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Rejects geometry that Bedrock cannot bind safely without changing valid UVs or transforms. */
final class EntityGeometryValidator {
    private EntityGeometryValidator() {
    }

    static String invalidReason(BedrockModel model) {
        if (model.model() == null || model.model().geometry() == null || model.model().geometry().isEmpty()) {
            return "model has no geometry";
        }

        Set<String> identifiers = new HashSet<>();
        for (Geometry geometry : model.model().geometry()) {
            if (geometry == null) return "geometry entry is null";
            Description description = geometry.description();
            String identifier = description == null ? null : description.identifier();
            if (identifier == null || identifier.isBlank()) return "geometry identifier is missing";
            if (!identifiers.add(identifier)) return "duplicate geometry identifier " + identifier;
            if (description.textureWidth() == null || !positiveFinite(description.textureWidth())) {
                return identifier + " has invalid texture width";
            }
            if (description.textureHeight() == null || !positiveFinite(description.textureHeight())) {
                return identifier + " has invalid texture height";
            }

            Map<String, Bones> bones = new HashMap<>();
            if (geometry.bones() == null || geometry.bones().isEmpty()) return identifier + " has no bones";
            for (Bones bone : geometry.bones()) {
                if (bone == null || bone.name() == null || bone.name().isBlank()) return identifier + " has an unnamed bone";
                if (bones.putIfAbsent(bone.name(), bone) != null) return identifier + " has duplicate bone " + bone.name();
                String vectorError = vectorError("bone " + bone.name() + " pivot", bone.pivot(), false);
                if (vectorError == null) vectorError = vectorError("bone " + bone.name() + " rotation", bone.rotation(), false);
                if (vectorError != null) return vectorError;
                if (bone.cubes() == null) continue;
                for (Cubes cube : bone.cubes()) {
                    if (cube == null) return "bone " + bone.name() + " has a null cube";
                    vectorError = vectorError("cube origin in " + bone.name(), cube.origin(), true);
                    if (vectorError == null) vectorError = vectorError("cube size in " + bone.name(), cube.size(), true);
                    if (vectorError == null) vectorError = vectorError("cube pivot in " + bone.name(), cube.pivot(), false);
                    if (vectorError == null) vectorError = vectorError("cube rotation in " + bone.name(), cube.rotation(), false);
                    if (vectorError != null) return vectorError;
                    for (float size : cube.size()) {
                        if (size < 0) return "cube size in " + bone.name() + " is negative";
                    }
                }
            }

            for (Bones bone : bones.values()) {
                if (bone.parent() != null && !bones.containsKey(bone.parent())) {
                    return "bone " + bone.name() + " has missing parent " + bone.parent();
                }
            }
            for (String bone : bones.keySet()) {
                Set<String> path = new HashSet<>();
                String current = bone;
                while (current != null) {
                    if (!path.add(current)) return "bone parent cycle contains " + current;
                    current = bones.get(current).parent();
                }
            }
        }
        return null;
    }

    private static boolean positiveFinite(float value) {
        return Float.isFinite(value) && value > 0;
    }

    private static String vectorError(String field, float[] values, boolean required) {
        if (values == null) return required ? field + " is missing" : null;
        if (values.length != 3) return field + " must contain three values";
        for (float value : values) {
            if (!Float.isFinite(value)) return field + " contains a non-finite value";
        }
        return null;
    }
}
