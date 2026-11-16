# Target-app node layout inspector

## Scope

This port keeps Freedom's existing accessibility service, gateway, floating-window host, overlay lifecycle, and activity presentation boundary. It does not import AutoJs6's script runtime or its floating-window service.

Manual inspection is restricted to the currently configured target package. Roots are accepted only after checking the package name on the actual accessibility root. Other applications, the IME, and system-owned windows are discarded. Same-package dialogs and popup windows are included when Android exposes their roots through `AccessibilityService.windows`.

## Interaction

- The existing floating launcher opens the node inspector or submits a manual multi-window capture.
- The inspector keeps detached `AccessibilityTreeSnapshot` data only. It offers a window picker, bounds map, hierarchy, node details, locator analysis, and a node-specific context menu.
- The context menu supports node information, hierarchy navigation, locator generation, parent selection, hiding one node or nodes sharing bounds, window switching, export, and exit.
- The full-layout preview draws up to 600 fresh bounds for the selected target-app window in a non-touchable accessibility overlay for 12 seconds. Input passes through to the target app.
- Selected-node highlight remains a separate short-lived action that refreshes and resolves the selected node in the same package/window before drawing.

## Safety and resource bounds

- The manual scan caps the number of included same-package windows at eight and divides the caller's node budget across those windows. Active/focused windows are prioritized. If more than eight target windows are exposed, or a node budget is reached, captured snapshots are marked as truncated.
- Framework `AccessibilityWindowInfo` and `AccessibilityNodeInfo` handles stay inside the Android gateway and are recycled there. UI receives detached snapshots and screen rectangles only.
- The full-layout overlay draws at most 600 rectangles and is automatically removed. It is cleared on target-app changes and before screenshot/OCR capture.
- Export is window-scoped when initiated from the node context menu and omits text/description content by default.

## Verification

The added unit/architecture tests cover manual multi-window capture routing, snapshot-only UI boundaries, package/window validation before screen drawing, and bounded overlay behavior. A full Android build and test run still needs to be performed in an environment with the Android SDK and the project's Gradle/Android dependencies installed.
