# LayerDefinition / ModelPart reader checkpoint

## VERIFIED: source and baseline

The implementation is based on PackConverter `6b088bf8c2aab6d416f7e0cf63f110511c453d09`. On 2026-09-09, live GitHub reads showed PRs #8 and #9 OPEN with `mergedAt: null`, and master at `0e3a21912fc85d876fc002f045e2b72e47eb2de3`. This change is a separate branch/PR; it does not merge, rebase or force-push any branch.

The supplied `MutantMonsters-v26.2.2-mc26.2.x-Fabric.jar` has SHA-256 `618b07e168abc83ebbdb548f5247eda0cabc90a40fa0e4ed3a587cbf2ce82e71`. The binary has no Java source files. `javap -p -c` was used to read the actual constructor and complete `MutantCreeperModel.createBodyLayer(CubeDeformation)` bytecode, rather than substitute source from another Minecraft version. The JAR's metadata identifies version 26.2.2 for Minecraft 26.2.

The exact resolved client runtime is Loom's `minecraft-merged-deobf-26.2.jar`, SHA-256 `2b269bba3f8ca67c87be45b00e4039d5a825dc8bb33fa4d00f5c7eed120bc510`. Its LayerDefinition, PartDefinition, PartPose, ModelPart, Cube, Polygon, Vertex and CubeDeformation bytecode was inspected. `CubeDeformation.NONE` constructs a zero-valued deformation. The selected model constructor only binds named children; procedural pose changes occur elsewhere and are not invoked.

The factory creates an empty root plus 12 named parts: pelvis, body, neck, head and four pairs of leg/fore-leg parts. Each named part contains one cube. The final `LayerDefinition.create(mesh, 128, 64)` fixes the texture dimensions. Four left-side cubes use `mirror()`. The source constants, hierarchy and mirror flags are transcribed in `converter/src/test/resources/entity/mutant-creeper-factory.json` for independent verification.

## VERIFIED: representation mapping

`PartDefinition.bake` calls both `setInitialPose(partPose)` and `loadPose(partPose)`. `ModelPart.loadPose` copies local x/y/z, xRot/yRot/zRot and all three scales. The reader uses the baked fields once; it does not add PartPose again. `translateAndRotate` translates local offsets divided by 16, then applies `Quaternionf.rotationZYX(zRot,yRot,xRot)`, then scale. `Vertex.x/y/z` remain pixels; `worldX/Y/Z` divide by 16 at render time and are deliberately not used.

The reader accumulates unrotated parent offsets for Bedrock's model-space pivots, retaining the bone parent relation and local rotations. The resulting mapping is `(javaX, 24-javaY, javaZ)` and XYZ radians to degrees without sign changes. This agrees with Citadel's final formula only after an independent inspection of ModelPart's actual pose semantics. The empty Java root is `(0,0,0)`, hence its Bedrock pivot is `(0,24,0)`; it must not be replaced by Bald Eagle's `(0,0,0)` Bedrock root. Pelvis becomes `(0,10,-3)`; body becomes `(0,22,-3)` even though its local offset is `(0,-12,0)`.

The independent test composes Java-to-editor and editor-to-Bedrock transforms at Blockbench commit **47e633e4a1338f957ee7baa0acbcf54da11e77df**:

- [LayerDefinition/PartPose template](https://github.com/JannisX11/blockbench/blob/47e633e4a1338f957ee7baa0acbcf54da11e77df/js/formats/java/modded_entity.js#L218), and [coordinate/rotation/cube transforms](https://github.com/JannisX11/blockbench/blob/47e633e4a1338f957ee7baa0acbcf54da11e77df/js/formats/java/modded_entity.js#L525).
- [Bedrock cube/bone export](https://github.com/JannisX11/blockbench/blob/47e633e4a1338f957ee7baa0acbcf54da11e77df/js/formats/bedrock/bedrock.js#L885), including the second X/rotation sign changes and top/bottom face-UV reversal.
- [Box UV layout and mirror handling](https://github.com/JannisX11/blockbench/blob/47e633e4a1338f957ee7baa0acbcf54da11e77df/js/outliner/types/cube.js#L1280).

`ModelPartGoldenTest` computes expected data from the transcribed factory constants via this composition. Its UV oracle uses box dimensions/texture offset/mirror, independently of the production reader's polygon vertex UVs. The oracle output was compared field-by-field against the real worker output: zero differences, including all mirrored cubes. The checked-in golden comes from the independent oracle. This is an executable numeric composition check, **not a run of the full Blockbench UI/importer or a Bedrock render check**.

Production reads each cube's actual polygons and pixel vertices, including inflation, per-vertex normalized UVs, and normals. In this runtime `Polygon.mirrorFacing` swaps X directions and the mirrored constructor reverses vertex ordering. Signed per-face UV extents encode that mapping without a second cube mirror operation. Degenerate side faces may be omitted; drawable planes keep a zero thickness. Non-rectangular faces or rotated/skewed UVs fail instead of being approximated. There are no block-size/overlap limits.

## VERIFIED: separate API and worker controls

`EntityConverter.convertLayer(Input)` selects the new `ModelPartGeometry` reader explicitly. Existing `convert(Input)`/`convertTo(...)` retain the Citadel route; the Citadel reader and Bald Eagle golden are unchanged. The new reader accepts a static `createBodyLayer()` or, when absent, the actual Minecraft `createBodyLayer(CubeDeformation)` with `NONE`. No arbitrary constructor parameters, model instances, renderer discovery or named-mod production rules are used. The API returns a validated JSON object; no new filesystem publisher is introduced.

The existing launcher was re-read before reuse: `-Xmx256m`, `-XX:MaxMetaspaceSize=128m`, maximum response 8,388,608 bytes, configured deadline, and forcible OS-process termination. New tests exercise the **layer route** for heap limit, output overflow, a busy-loop factory whose PID must be dead after timeout, and a shutdown hook that cannot delay successful completion. `Runtime.halt` is used by the worker after success/error; a hung worker cannot cooperatively call halt, so the parent's `destroyForcibly` enforces the deadline. This is not an OS security sandbox.

The real-JAR test explicitly requires Java 25, verifies the supplied binary hash and invokes `convertLayer` through the same launcher with a 20-second deadline. Normal CI has no supplied JARs and skips opt-in binary tests; a green CI build is not evidence that the real JAR ran there.

## VERIFIED: local validation run

On 2026-09-09, the full repository `build --offline -PtestJavaVersion=25 -PentityFixtureClasspath=...` passed: **39 tests, 0 failures, 0 skips**. Gradle/compiler used Java 21 and test/worker JVMs used Java 25. This consists of the previous 32 regressions plus 5 ModelPart/process tests, 1 independent factory/golden composition test and 1 exact Mutant Creeper JAR test. The Bald Eagle JAR/golden and the old Citadel rejection of a ModelPart input remain in that run. No test was removed to obtain a pass. The full build log is `outputs/modelpart-evidence/modelpart-build-real-tests.log`, accompanied by the JUnit XML files.

## NOT VERIFIED

- Any model other than the selected `MutantCreeperModel.createBodyLayer(CubeDeformation.NONE)` factory in this mod. A passing Bald Eagle regression and this passing factory are evidence for two particular model inputs, **not whole-mod conversion success**.
- Full official schema validation, actual Blockbench UI behavior, Bedrock appearance/UV orientation/rendering, or full mod/server startup.
- Non-unit or negative ModelPart scale. ModelPart propagates scale to descendants, unlike Citadel's optional local-only scale. The reader rejects any scale other than 1. It also rejects hidden/skipDraw parts, cycles/shared identities, non-finite values, unsupported face layouts and UVs. None of these unsupported cases was present in the selected default factory; synthetic negative cases verify explicit failure.
- Arbitrary factory names/signatures, nonzero CubeDeformation variants, renderer-time transforms or setupAnim state. These must not be silently inferred from this fixture.

## NOT IMPLEMENTED / outside this change

Hydraulic discovery/cache and Geyser registration are untouched. No animation, sound, render controller, proxy selector or server integration is implemented here. Tests return geometry only. No merge, rebase, force-push, replacement-mod download or whole-pipeline completion claim is part of this checkpoint. Local logs, exact committed revision, test counts and CI links are recorded in the accompanying task `outputs/modelpart-evidence/checkpoint.md`.
