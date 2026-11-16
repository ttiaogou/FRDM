# Freedom App Icon Rendering

The SVG files in this folder and `../FloatingButton/collapsed.svg` are the visual source of truth.

## Android resources

- Launcher icon: `app/src/main/res/drawable-nodpi/ic_freedom_master.png`
  - Rendered from `freedom_icon_master.svg` at 1080 × 1080 RGBA.
  - Referenced by `app/src/main/AndroidManifest.xml` as `@drawable/ic_freedom_master`.
- Floating launcher glyph: `app/src/main/res/drawable-nodpi/ic_floating_launcher_master.png`
  - Rendered from `../FloatingButton/collapsed.svg` at 1080 × 1080 RGBA and referenced by `MonitorOverlay` as `R.drawable.ic_floating_launcher_master`.
  - Uses a transparent canvas so the existing circular floating-button background remains independent.

Both raster resources are rendered directly from the SVG gradients. Do not re-create their colors by sampling screenshots or by replacing the gradients with flat fills.

## Exact icon palette

- Outer path gradient: `#314D3A` → `#17281F`
- Inner path gradient: `#E1F66A` → `#B9DF3B` → `#7DAE25`
- Middle node fill: `#F8F7F0`
- Middle node stroke: `#23372B`
- Main icon background: `#F8F7F0`

`app/src/main/res/drawable/ic_freedom.xml` is retained as the existing notification small-icon resource. It is intentionally not the launcher artwork.
