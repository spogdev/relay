# Mojang (main / 26.1.2) → Yarn (1.21.11) translation table

Built by diffing confirmed imports on both branches. Left = main, right = 1.21.11-port.

## Confirmed 1:1 (both branches use these)
| Mojang (main) | Yarn (1.21.11) |
|---|---|
| client.Minecraft | client.MinecraftClient |
| client.gui.Font | client.font.TextRenderer |
| client.gui.GuiGraphicsExtractor | client.gui.DrawContext |
| client.gui.components.PlayerFaceExtractor | client.gui.PlayerSkinDrawer |
| client.gui.screens.Screen | client.gui.screen.Screen |
| client.gui.components.Button | client.gui.widget.ButtonWidget |
| client.gui.components.AbstractWidget | client.gui.widget.ClickableWidget |
| client.gui.components.CycleButton | client.gui.widget.CyclingButtonWidget |
| client.gui.components.AbstractSliderButton | client.gui.widget.SliderWidget |
| client.gui.components.EditBox | client.gui.widget.TextFieldWidget |
| client.gui.components.Tooltip | client.gui.tooltip.Tooltip |
| client.gui.components.toasts.SystemToast | client.toast.SystemToast |
| client.gui.narration.NarrationElementOutput | client.gui.screen.narration.NarrationMessageBuilder |
| client.input.MouseButtonEvent (Click ctx) | client.gui.Click |
| client.multiplayer.PlayerInfo | client.network.PlayerListEntry |
| client.multiplayer.ClientPacketListener | client.network.ClientPlayNetworkHandler |
| client.multiplayer.ClientLevel | client.world.ClientWorld |
| client.multiplayer.ServerData | client.network.ServerInfo |
| client.player.AbstractClientPlayer | client.network.AbstractClientPlayerEntity |
| client.player.LocalPlayer | client.network.ClientPlayerEntity |
| client.KeyMapping | client.option.KeyBinding |
| client.DeltaTracker | client.render.RenderTickCounter |
| client.renderer.MultiBufferSource | client.render.VertexConsumerProvider |
| client.renderer.RenderPipelines | client.gl.RenderPipelines |
| client.resources.sounds.SimpleSoundInstance | client.sound.PositionedSoundInstance |
| client.resources.DefaultPlayerSkin | client.util.DefaultSkinHelper |
| core.component.DataComponents | component.DataComponentTypes |
| core.registries.BuiltInRegistries | registry.Registries |
| core.registries.Registries | registry.RegistryKeys |
| core.Registry | registry.Registry |
| core.registries (ResourceKey) | registry.RegistryKey |
| network.chat.Component | text.Text |
| network.chat.MutableComponent | text.MutableText |
| network.chat.CommonComponents | screen.ScreenTexts |
| resources.Identifier | util.Identifier |
| resources.ResourceKey | registry.RegistryKey |
| sounds.SoundEvent | sound.SoundEvent |
| sounds.SoundEvents | sound.SoundEvents |
| world.entity.EquipmentSlot | entity.EquipmentSlot |
| world.entity.player.PlayerSkin | entity.player.SkinTextures |
| world.item.ItemStack | item.ItemStack |
| world.phys.Vec3 | util.math.Vec3d |
| world.level.Level | world.World |
| util.Mth | util.math.MathHelper |

## Need to look up (used on main, no direct twin on this branch yet)
- ChatFormatting → Formatting
- client.Camera → client.render.Camera
- client.gui.components.AbstractSliderButton → SliderWidget (have it)
- client.input.InputWithModifiers → ? (Click carries modifiers in yarn)
- client.multiplayer.ServerList → client.network.ServerList (likely)
- client.multiplayer.resolver.ServerAddress → client.network.ServerAddress
- client.renderer.rendertype.RenderTypes → client.render.RenderLayer
- network.chat.ClickEvent → text.ClickEvent
- network.chat.HoverEvent → text.HoverEvent
- network.chat.Style → text.Style
- world.level.ClipContext → world.RaycastContext
- world.phys.HitResult → util.hit.HitResult

## Method/API drift (not just names)
- GuiGraphics.drawString → DrawContext.drawText(textRenderer, ...)
- GuiGraphics.blitSprite → DrawContext.drawGuiTexture
- itemDecorations / renderItemDecorations → DrawContext.drawItemInSlot / drawStackOverlay
- Component.translatable → Text.translatable
- Component.literal → Text.literal
- LevelRenderEvents (26.1) → WorldRenderEvents (fabric, both) — CHECK the fabric api version
- setSkipDraw / render-state APIs differ; PingRenderer may need real rework
