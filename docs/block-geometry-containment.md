# Oversized block geometry containment

This change addresses the cause documented in GeyserMC/Hydraulic #11 and #12: oversized block geometry can disable custom-block rendering in the surrounding chunk. It does not implement Java mob model conversion, and no original Ad Astra world/JAR reproduction is claimed.

ModelConverter checks its generated block cubes after rotation, including aggregate extents, historical error bounds, finite numbers and negative sizes. Out-of-bounds geometry becomes one 16-pixel cube with the original geometry identifier/output filename and an existing material variable, with an explicit warning containing the resource key and reason. It does not prune individual cubes or leave dangling registered geometry names. This is intentionally a visible loss of the unsupported block shape, not a claim of visual equivalence. The conservative 30-pixel aggregate limit and historical error envelope target safety; future client-version-specific limits need their own tests. Item geometry is excluded; runtime mob geometry must use an independent pipeline. A namespace containing `entity` no longer misclassifies ordinary block models as mobs.

Validation: before implementation, BlockGeometryContainmentTest ran on JVM 25 and the oversized assertion failed while the normal slab passed. After implementation, all 15 converter tests pass on JVM 25, including rotated and aggregate bounds, invalid numbers, namespace classification, one explicit warning, healthy sibling preservation and golden JSON through BedrockResourcePack.export. Test fixture geometry is synthetic, based on the documented failure class, not passed off as an original mod model.

Reproduce on this repository's Gradle 8.14.4: launch Gradle with JDK 21 and run `./gradlew :converter:test -PtestJavaVersion=25` with a JDK 25 toolchain installed. The wrapper's Kotlin runtime cannot launch with the locally installed 25.0.4.1 version string. Tests default to Java 21 in the existing CI. Test dependencies/platform wiring were missing on master and are included here.

Remaining gates: bind this exact converter in Hydraulic, invalidate previously converted caches, run the Hydraulic build/headless tests, and have a real Bedrock client verify original chunk rendering before release. Automatic tests are not evidence of actual Bedrock client rendering.

Sources: https://github.com/GeyserMC/Hydraulic/issues/11 , https://github.com/GeyserMC/Hydraulic/issues/12 , https://learn.microsoft.com/en-us/minecraft/creator/documents/customblockoversized?view=minecraft-bedrock-stable
