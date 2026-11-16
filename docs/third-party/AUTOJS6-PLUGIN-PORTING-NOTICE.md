# AutoJs6 OCR Plugin Core Port Notice

Freedom's OCR plugin interoperability layer is adapted from the following upstream projects:

- **AutoJs6**: <https://github.com/SuperMonster003/AutoJs6>
  - inspected revision: `b95b98b339c56ab12ba20b279d2b998eb6e2778c`
  - relevant upstream areas: `plugin-api/common-plugin-api`, `plugin-api/paddle-ocr-api`, `app/src/main/java/org/autojs/autojs/core/plugin/ocr/OcrPluginHost.kt`, `OcrPluginSelection.kt`, `PluginInstaller.kt`, and the plugin-center enablement/discovery flow.
- **AutoJs6 Paddle OCR PP-OCRv5 Plugin**: <https://github.com/SuperMonster003/AutoJs6-Plugin-Paddle-OCR-PP-OCRv5>
  - inspected revision: `522b2d4183de8963a070ec0f7b0db655d18c84e6`
  - relevant upstream areas: `PpOcrV5PluginService.kt`, `PpOcrV5EngineHolder.kt`, and `ImageFdDecoder.kt`.

Both upstream repositories publish the relevant source under the **Mozilla Public License 2.0 (MPL-2.0)**. A copy of that license is retained in [MPL-2.0.txt](MPL-2.0.txt).

## Ported/adapted areas

- The shared `PluginInfo` Parcelable and common plugin capability keys.
- The OCR AIDL wire contract (`IOcrPlugin`, `OcrOptions`, `OcrResult`) and OCR option/capability keys.
- OCR service discovery using the upstream `org.autojs.plugin.PADDLE_OCR` action and `paddle-ocr` category.
- Plugin metadata-based selection, installed/enabled state handling, refused-bind wake retry, Binder-death handling, idle unbind and package-oriented catalog installation.
- The OCR plugin service shape, engine-holder caching/release pattern, and file-descriptor image decoding pattern.

## Freedom-specific adaptations

- The host permission is `com.freedom.permission.BIND_OCR_PLUGIN`, protected by Android's `signature` permission level. AutoJs6's host-owned permission is not reused because it belongs to a different host application. A no-display `OcrPluginWakeActivity` adapts AutoJs6's stopped-package wake pattern before retrying a refused service bind.
- Freedom retains its existing configuration-page entry and dialog presentation instead of transplanting AutoJs6's entire Plugin Center UI. The existing window, menu and gesture flows remain owned by Freedom.
- The plugin retains Freedom's existing PP-OCRv6_small model catalog/downloader and plugin-private model storage. The AIDL `PluginInfo` advertises the `paddle-ocr` engine and the `v6-small` variant.
- Legacy Freedom OCR AIDL files are retained for source compatibility; the new host path uses the shared AutoJs6-compatible OCR API module.

The source in the shared API module and runtime adapters is derived from or adapted to MPL-2.0-covered upstream material. Changes made for Freedom are documented in the source-file headers and this notice.
