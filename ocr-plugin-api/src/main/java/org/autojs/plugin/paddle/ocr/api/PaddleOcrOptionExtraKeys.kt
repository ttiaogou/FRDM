/* Ported from AutoJs6 paddle-ocr-api; see docs/third-party/AUTOJS6-PLUGIN-PORTING-NOTICE.md. */
package org.autojs.plugin.paddle.ocr.api

object PaddleOcrOptionExtraKeys {
    const val ENGINE_ID = "engineId"
    const val ENGINE = "engine"
    const val VARIANT = "variant"
    const val PROFILE = "profile"

    const val IMAGE_FORMAT = "imageFormat"
    const val IMAGE_QUALITY = "imageQuality"
    const val RAW_IMAGE = "rawImage"
    const val RAW_WIDTH = "rawWidth"
    const val RAW_HEIGHT = "rawHeight"
    const val RAW_STRIDE = "rawStride"
    const val RAW_CONFIG = "rawConfig"

    const val DET_LIMIT_SIDE_LEN = "detLimitSideLen"
    const val DET_LIMIT_TYPE = "detLimitType"
    const val DET_MAX_SIDE_LIMIT = "detMaxSideLimit"
    const val DET_THRESH = "detThresh"
    const val DET_BOX_THRESH = "detBoxThresh"
    const val DET_UNCLIP_RATIO = "detUnclipRatio"
    const val REC_BATCH_SIZE = "recBatchSize"
    const val USE_CLS = "useCls"
}
