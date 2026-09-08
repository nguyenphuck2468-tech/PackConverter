package org.geysermc.pack.converter.type.model;

import org.geysermc.pack.bedrock.resource.models.entity.modelentity.geometry.Bones;
import org.geysermc.pack.bedrock.resource.models.entity.modelentity.geometry.bones.Cubes;

import java.util.Arrays;
import java.util.List;

/** Bounds for the flat, single-axis cube bones emitted by ModelConverter only.
 * Never apply block bounds to an entity model. Hydraulic #11/#12 records the
 * historical chunk-wide failure outside [-0.875, 1.875] in block coordinates.
 * Current block guidance also limits the aggregate size to 30 pixels.
 */
final class BlockModelBounds {
    private BlockModelBounds() { }

    static String failure(List<Bones> bones) {
        double[] min = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        double[] max = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (Bones bone : bones) {
            for (Cubes cube : bone.cubes()) {
                float[] origin = cube.origin();
                float[] size = cube.size();
                if (!finite(origin) || !finite(size)) return "non-finite cube";
                for (float extent : size) if (extent < 0) return "negative cube size";
                float[] pivot = cube.pivot();
                float[] rotation = cube.rotation();
                if ((pivot != null && !finite(pivot)) || (rotation != null && !finite(rotation))) {
                    return "non-finite cube transform";
                }
                for (int corner = 0; corner < 8; corner++) {
                    double[] point = new double[3];
                    for (int axis = 0; axis < 3; axis++) {
                        point[axis] = origin[axis] + ((corner >> axis & 1) == 0 ? 0 : size[axis]);
                    }
                    if (rotation != null) {
                        // ModelConverter emits only one nonzero rotation axis per cube.
                        for (int axis = 0; axis < 3; axis++) {
                            if (rotation[axis] == 0) continue;
                            int a = (axis + 1) % 3, b = (axis + 2) % 3;
                            double pa = pivot == null ? origin[a] + size[a] / 2.0 : pivot[a];
                            double pb = pivot == null ? origin[b] + size[b] / 2.0 : pivot[b];
                            double radians = Math.toRadians(rotation[axis]);
                            double x = point[a] - pa, y = point[b] - pb;
                            point[a] = pa + x * Math.cos(radians) - y * Math.sin(radians);
                            point[b] = pb + x * Math.sin(radians) + y * Math.cos(radians);
                        }
                    }
                    for (int axis = 0; axis < 3; axis++) {
                        min[axis] = Math.min(min[axis], point[axis]);
                        max[axis] = Math.max(max[axis], point[axis]);
                    }
                }
            }
        }
        for (int axis = 0; axis < 3; axis++) {
            // Bedrock geometry X/Z are centered; block-space Y starts at the bottom.
            double lower = axis == 1 ? -14 : -22;
            double upper = axis == 1 ? 30 : 22;
            if (min[axis] < lower - 1e-5 || max[axis] > upper + 1e-5
                    || max[axis] - min[axis] > 30 + 1e-5) {
                return "block bounds exceeded: " + Arrays.toString(min) + " to " + Arrays.toString(max);
            }
        }
        return null;
    }

    private static boolean finite(float[] vector) {
        return vector.length == 3 && Float.isFinite(vector[0]) && Float.isFinite(vector[1]) && Float.isFinite(vector[2]);
    }
}
