# PingRenderer reimplementation notes — 1.21.11

The `main` (26.1) PingRenderer does NOT port mechanically. Confirmed API facts for 1.21.11
(yarn `1.21.11+build.6`, fabric-api `0.141.5+1.21.11`, fabric-rendering-v1 `16.2.10`):

## The event (was `LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN`)
- Package is `net.fabricmc.fabric.api.client.rendering.v1.world` (note `.world`).
- `WorldRenderContext` exposes ONLY: `commandQueue()`, `matrices()` (MatrixStack), `consumers()`
  (VertexConsumerProvider), plus `gameRenderer()`, `worldRenderer()`, `worldState()`.
  **No `camera()`, no `tickDelta()`.**
- Events: `START_MAIN`, `BEFORE_ENTITIES`, `AFTER_ENTITIES`, `BEFORE_TRANSLUCENT`,
  `BEFORE_BLOCK_OUTLINE`, `END_MAIN`, etc. There is **no AFTER_TRANSLUCENT_TERRAIN**.
  Closest for see-through markers that composite over water: try `AFTER_ENTITIES` first; if markers
  get overdrawn by translucent terrain, fall back to `END_MAIN` (drawn to framebuffer, last).
- Get the camera from `MinecraftClient.getInstance().gameRenderer.getCamera()` (yarn: `getCamera()`),
  NOT from the context.

## Labels (name/distance text) — THIS PART IS CLEAN
- `Font.drawInBatch(...)` → `TextRenderer.draw(String, x, y, argb, shadow, Matrix4f,
  VertexConsumerProvider, TextRenderer.TextLayerType, backgroundColor, light)`. Same shape.
- `Font.DisplayMode.SEE_THROUGH` → `TextRenderer.TextLayerType.SEE_THROUGH` (also NORMAL,
  POLYGON_OFFSET). Use SEE_THROUGH for through-walls, NORMAL otherwise.
- `Font.width` → `TextRenderer.getWidth`; `Font.lineHeight` → `TextRenderer.fontHeight`.

## Textured pin + ring + label-box quads — THIS IS THE HARD PART
- **All the named text/nametag RenderLayer factories are GONE.** `RenderTypes.textSeeThrough`,
  `.text`, `.textBackground`, `.textBackgroundSeeThrough` do not exist.
- The only public RenderLayer factory is `RenderLayer.of(String name, RenderSetup setup)`.
  Everything now goes through a `RenderSetup`. You must build a `RenderSetup` describing:
  texture, blend, depth-test (disabled for see-through), cull, the vertex format
  (POSITION_COLOR_TEXTURE_LIGHT for the pin/ring; POSITION_COLOR_LIGHT for boxes), and a shader/
  pipeline. Investigate `net.minecraft.client.render.RenderSetup`, `RenderPipeline`, and how vanilla
  builds its own layers (look at how `RenderLayer` static fields are constructed internally, or how
  entity/gui code builds ad-hoc layers) to get a working setup. `RenderPipelines` (yarn
  `net.minecraft.client.gl.RenderPipelines`) already exists on this branch and may have a reusable
  pipeline constant for textured+translucent geometry.
- Vertex building: `VertexConsumer.addVertex(MatrixStack.Entry, x, y, z)` then
  `.color(...)`/`.texture(u,v)`/`.light(int)`/`.normal(entry, nx,ny,nz)` (yarn method names:
  `color`, `texture`, `light`, `normal` — NOT setColor/setUv/setLight/setNormal).
- Billboard: `matrices.multiply(camera.getRotation())`. `camera.getPos()` for position,
  `camera.getRotation()` (Quaternionf).

## Alternative if `RenderLayer.of`/`RenderSetup` proves too deep
If building a RenderSetup for the textured pin is intractable, a pragmatic fallback that still gives
the user a working feature: draw the pin/ring as **untextured coloured quads** (a coloured diamond
or ring) using whatever translucent no-texture layer is easiest to construct, and keep the text
labels (which port cleanly). Document clearly that the pin art (ping.png) isn't used yet. A visible
coloured marker + working labels is far better than the current no-op. Prefer the real textured
version if achievable.

## The isHovered latch — port as-is
The `shown` map + `passesCrosshairGate` logic is pure math (dot product of look vs direction), no MC
render API. Restore it exactly from `git show main:...PingRenderer.java`. This is what makes the
ping key remove-by-looking work again. The look vector comes from `camera` forward, or
`mc.player.getRotationVector()`.

## Source of truth
`git show main:src/main/java/dev/spog/teamlocator/client/render/PingRenderer.java` is the full 521-line
original with all the tuning constants and their rationale in comments. Preserve every constant value
and every explanatory comment; only the rendering plumbing changes.
