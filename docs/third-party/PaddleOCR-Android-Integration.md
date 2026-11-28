# PaddleOCR Android integration

Freedom no longer packages PaddleOCR runtime or model files in the main application APK. The host uses the AutoJs6-compatible OCR AIDL/Parcelable contract and Action/Category service discovery from the lightweight `:ocr-plugin-api` module. PaddleOCR is hosted in the separately-installed `:ocr-plugin` application module, and the pinned PP-OCRv6_small model files are verified at plugin build time and packaged into the separate plugin APK. The full upstream port notice and adapted file list are in `AUTOJS6-PLUGIN-PORTING-NOTICE.md`.

## Ownership and dependencies

- Shared API (`:ocr-plugin-api`): only the AutoJs6-compatible PluginInfo/AIDL parcelables and capability keys; it must not depend on OCR runtime libraries.
- Main application (`:app`): lightweight OCR host, metadata-based selection, plugin catalog installer and Freedom business adapter. It must not depend on ONNX Runtime, OpenCV, PaddleOCR SDK source, or OCR model assets.
- OCR plugin (`:ocr-plugin`): PaddleOCR Android ONNX SDK, ONNX Runtime Android 1.21.1, the official OpenCV Android AAR (`org.opencv:opencv:4.9.0`), image preprocessing/postprocessing, and model manager.
- Model packaging: PP-OCRv6_small detection/recognition ONNX files and recognition YAML are downloaded and SHA-256 verified during the plugin build, then packaged into the OCR plugin APK. At runtime they are installed into plugin-private `noBackupFilesDir/ocr/models/ppocrv6_small/`; first-use inference does not require a model-host connection.

## Upstream SDK source

The plugin build downloads the official PaddleOCR Android SDK Kotlin sources from the immutable PaddleOCR commit `dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf`. Six files are locally adapted: the first two let the engine read model and dictionary files from absolute paths in plugin-private storage instead of requiring APK assets; the third preserves OpenCV linker diagnostics; the last three pass the request-specific detection resize limit through the existing inference path so different image sizes reuse the same ONNX sessions without changing preprocessing:

- `com/paddle/ocr/engine/ORTSessionManager.kt`
- `com/paddle/ocr/model/ModelConfig.kt`
- `com/paddle/ocr/util/OpenCVUtils.kt` (uses the official OpenCV Android AAR's `OpenCVLoader.initLocal()` API and preserves loader diagnostics).
- `com/paddle/ocr/PaddleOCR.kt`
- `com/paddle/ocr/engine/OCREngine.kt`
- `com/paddle/ocr/engine/DetectionEngine.kt`

The engine holder retains its single cache; only the per-image `detMaxSideLimit` is excluded from the engine key. Each recognition call receives the original resolved limit (explicit option, image long side, or default). Model version, thread count and other existing configuration fields remain cache-key inputs. The original resize algorithm, thresholds, model assets and session release behavior are unchanged.

The OpenCV dependency is the official Android AAR maintained by the OpenCV project. The previous `com.quickbirdstudios:opencv:4.5.3` dependency is intentionally not used because its packaged native library failed to load on the target device with an unresolved `__sfp_handle_exceptions` symbol.

All other SDK source files are generated under the plugin build directory. The upstream license is copied to the plugin's `THIRD_PARTY_NOTICES` asset directory. PaddleOCR is distributed under Apache License 2.0: https://github.com/PaddlePaddle/PaddleOCR/blob/dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf/LICENSE.

## PP-OCRv6_small models

The plugin Gradle prepareBundledOcrModels task fetches or reuses the detection and recognition ONNX models and recognition YAML from immutable revisions in the bundled catalog, checking minimum sizes and pinned SHA-256 values. OcrModelManager installs these assets into plugin-private storage, verifies all three hashes and records them in a local manifest before activating the staged directory. A remote download path still exists for models without bundled assets.

The fixed bundled model does not require the device to contact a model host on first recognition; uncached build-time downloads and optional remote models require network access. Actual offline device inference remains to be verified. See `docs/ocr/OCR-PLUGIN-ARCHITECTURE.md` for the package split, AutoJs6-compatible plugin discovery, separate host/plugin downloads, signing requirements, and build flow.
