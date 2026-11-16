# Freedom Floating Button — Interaction Master

## Collapsed
- Use `AppIcon/freedom_icon_master.svg` geometry without a wordmark.
- Preserve the Option B four anchors: lower-left filled circle, upper-middle ring, lower-middle ring, upper-right rounded square.
- Preserve the single continuous S-shaped path.

## Expanded
- Keep a vertical two-entry stack.
- Entry 1: **节点可视化** — open the existing global node-layout visualization request.
- Entry 2: **导出采集数据** — invoke the existing collection/export flow and its existing archive/path selection.
- Do not expose the removed three legacy actions.
- Keep the menu window geometry capable of adding another real action later; do not display an inactive placeholder.

## Interaction
- Tapping the collapsed launcher expands the menu.
- Tapping outside collapses it.
- Tapping either entry first collapses the menu, then calls the existing feature.
- Dragging and edge snapping remain unchanged.
- The label pill and round icon share one touch target.
