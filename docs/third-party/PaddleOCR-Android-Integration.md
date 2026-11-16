# PaddleOCR Android integration

Freedom no longer packages PaddleOCR runtime or model files in the main application APK. The host uses the AutoJs6-compatible OCR AIDL/Parcelable contract and Action/Category service discovery from the lightweight `:ocr-plugin-api` module. PaddleOCR is hosted in the separately-installed `:ocr-plugin` application module, and PP-OCRv6_small model files are downloaded by that plugin at runtime. The full upstream port notice and adapted file list are in `AUTOJS6-PLUGIN-PORTING-NOTICE.md`.

## Ownership and dependencies

- Shared API (`:ocr-plugin-api`): only the AutoJs6-compatible PluginInfo/AIDL parcelables and capability keys; it must not depend on OCR runtime libraries.
- Main application (`:app`): lightweight OCR host, metadata-based selection, plugin catalog installer and Freedom business adapter. It must not depend on ONNX Runtime, OpenCV, PaddleOCR SDK source, or OCR model assets.
- OCR plugin (`:ocr-plugin`): PaddleOCR Android ONNX SDK, ONNX Runtime Android 1.21.1, the official OpenCV Android AAR (`org.opencv:opencv:4.9.0`), image preprocessing/postprocessing, and model manager.
- Model packaging: PP-OCRv6_small detection/recognition ONNX files and recognition YAML are downloaded and SHA-256 verified during the plugin build, then packaged into the OCR plugin APK. At runtime they are installed into plugin-private `noBackupFilesDir/ocr/models/ppocrv6_small/`; first-use inference does not require a model-host connection.

## Upstream SDK source

The plugin build downloads the official PaddleOCR Android SDK Kotlin sources from the immutable PaddleOCR commit `dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf`. Three files are locally adapted: the first two let the engine read model and dictionary files from absolute paths in plugin-private storage instead of requiring APK assets; the third preserves the original OpenCV linker exception and records ABI/native-library diagnostics instead of reducing the failure to a Boolean:

- `com/paddle/ocr/engine/ORTSessionManager.kt`
- `com/paddle/ocr/model/ModelConfig.kt`
- `com/paddle/ocr/util/OpenCVUtils.kt` (uses the official OpenCV Android AAR's `OpenCVLoader.initLocal()` API and preserves loader diagnostics).

The OpenCV dependency is the official Android AAR maintained by the OpenCV project. The previous `com.quickbirdstudios:opencv:4.5.3` dependency is intentionally not used because its packaged native library failed to load on the target device with an unresolved `__sfp_handle_exceptions` symbol.

All other SDK source files are generated under the plugin build directory. The upstream license is copied to the plugin's `THIRD_PARTY_NOTICES` asset directory. PaddleOCR is distributed under Apache License 2.0: https://github.com/PaddlePaddle/PaddleOCR/blob/dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf/LICENSE.

## PP-OCRv6_small models

The model manager fetches detection and recognition ONNX models from the official PaddlePaddle Hugging Face repositories using immutable revisions. It checks the detection and recognition ONNX SHA-256 values against pinned constants, verifies the config file and records all three file hashes in a local manifest, then atomically activates the model directory.

The first download requires internet access. Once installed, inference is local/offline. See `docs/ocr/OCR-PLUGIN-ARCHITECTURE.md` for the package split, AutoJs6-compatible plugin discovery, separate host/plugin downloads, signing requirements, and build flow.
