package com.freedom.adapters.android.gestures

import android.content.Context
import com.freedom.application.ports.GestureCalibrationRecord
import com.freedom.application.ports.GestureCalibrationResult
import com.freedom.application.ports.GestureSettingsRepository
import android.os.Build
import android.view.WindowManager
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Local device calibration for the scroll-path micro offset.
 *
 * It evaluates MotionEvent timestamps received by Freedom's own calibration surface.
 * It does not sample or send gestures to the configured third-party app.
 */
object GestureTuning {
    private const val PREF_KEY_PREFIX = "account_browse_gesture_calibration"

    const val MIN_VALID_TRIALS = 3
    const val MIN_INTERVAL_COUNT = 12

    const val CALIBRATION_GESTURE_DURATION_MS = 700L
    const val CALIBRATION_TRIAL_GAP_MS = 350L
    private const val MIN_RECOMMENDED_FREQUENCY_HZ = 0.5f
    private const val MAX_RECOMMENDED_FREQUENCY_HZ = 10f
    private const val SAMPLES_PER_OFFSET_CYCLE = 4f
    private const val SAFETY_FACTOR = 0.80f

    fun evaluateSamples(trials: List<List<Long>>): GestureCalibrationResult {
        val usableTrials = trials
            .map { values -> values.distinct().sorted() }
            .filter { it.size >= com.freedom.application.ports.GestureCalibrationContract.MIN_POINTS_PER_TRIAL }

        if (usableTrials.size < MIN_VALID_TRIALS) {
            return invalidResult(
                "有效测试轨迹不足：只收到 ${usableTrials.size} 组完整轨迹，" +
                    "至少需要 $MIN_VALID_TRIALS 组。"
            )
        }

        val intervals = usableTrials.flatMap { times ->
            times.zipWithNext { first, second -> second - first }
        }.filter { it in 1L..250L }.map { it.toInt() }.sorted()

        if (intervals.size < MIN_INTERVAL_COUNT) {
            return invalidResult(
                "有效采样时间间隔不足：收到 ${intervals.size} 个，" +
                    "至少需要 $MIN_INTERVAL_COUNT 个。请重新采样。"
            )
        }

        val median = percentile(intervals, 0.50)
        val p85 = percentile(intervals, 0.85).coerceAtLeast(median)
        if (median <= 0 || p85 <= 0) {
            return invalidResult("采样时间戳无效，无法计算设备采样间隔。")
        }

        val jitterPercent = (((p85 - median).toFloat() / median.toFloat()) * 100f)
            .roundToInt()
            .coerceAtLeast(0)

        // Use the slower P85 interval, at least four received samples per wave cycle,
        // and an additional 20% margin. The result is intentionally capped at 10 Hz.
        val computed = (1_000f / (p85 * SAMPLES_PER_OFFSET_CYCLE)) * SAFETY_FACTOR
        val recommended = min(computed, MAX_RECOMMENDED_FREQUENCY_HZ)
            .coerceIn(MIN_RECOMMENDED_FREQUENCY_HZ, MAX_RECOMMENDED_FREQUENCY_HZ)

        return GestureCalibrationResult(
            valid = true,
            message = "设备轨迹采样已完成。",
            recommendedFrequencyHz = recommended,
            medianIntervalMs = median,
            p85IntervalMs = p85,
            jitterPercent = jitterPercent,
            intervalCount = intervals.size,
            completedTrialCount = usableTrials.size
        )
    }

    fun saveCalibration(
        context: Context,
        result: GestureCalibrationResult,
        repository: GestureSettingsRepository
    ) {
        require(result.valid) { "Only a valid calibration result can be saved." }
        repository.saveCalibration(
            preferencePrefix(context),
            GestureCalibrationRecord(
                frequencyHz = result.recommendedFrequencyHz,
                medianIntervalMs = result.medianIntervalMs,
                p85IntervalMs = result.p85IntervalMs,
                jitterPercent = result.jitterPercent,
                sampleCount = result.intervalCount,
                trialCount = result.completedTrialCount,
                completedAtMillis = System.currentTimeMillis()
            )
        )
    }

    fun isCalibrated(context: Context, repository: GestureSettingsRepository): Boolean =
        repository.readCalibration(preferencePrefix(context), fallbackFrequencyHz(context)) != null

    fun currentFrequencyHz(context: Context, repository: GestureSettingsRepository): Float =
        repository.readCalibration(preferencePrefix(context), fallbackFrequencyHz(context))?.frequencyHz
            ?.coerceIn(MIN_RECOMMENDED_FREQUENCY_HZ, MAX_RECOMMENDED_FREQUENCY_HZ)
            ?: fallbackFrequencyHz(context)

    fun configurationSubtitle(context: Context, repository: GestureSettingsRepository): String =
        if (isCalibrated(context, repository)) {
            "本机已校准 · ${formatFrequency(currentFrequencyHz(context, repository))}Hz · 只读"
        } else {
            "尚未采样 · 使用保守估算值 · 点击开始校准"
        }

    fun calibrationSummary(context: Context, repository: GestureSettingsRepository): String {
        val calibration = repository.readCalibration(preferencePrefix(context), fallbackFrequencyHz(context))
            ?: return "当前状态：尚未完成本机自动采样。\n" +
                "当前临时使用保守估算频率：${formatFrequency(fallbackFrequencyHz(context))}Hz。"

        return buildString {
            append("本机已完成自动校准\n")
            append("自动推荐频率：").append(formatFrequency(calibration.frequencyHz)).append("Hz（只读）\n")
            append("完整测试组数：").append(calibration.trialCount).append("\n")
            append("有效轨迹间隔：").append(calibration.sampleCount).append(" 个\n")
            append("中位采样间隔：").append(calibration.medianIntervalMs).append("ms\n")
            append("P85采样间隔：").append(calibration.p85IntervalMs).append("ms\n")
            append("采样波动参考：").append(calibration.jitterPercent).append("%\n")
            append("校准时间：")
            if (calibration.completedAtMillis > 0L) {
                append(
                    java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm",
                        java.util.Locale.getDefault()
                    ).format(java.util.Date(calibration.completedAtMillis))
                )
            } else {
                append("未知")
            }
            append("\n\n需要重新评估时，点击“重新采样”。")
        }
    }

    fun formatFrequency(value: Float): String =
        String.format(java.util.Locale.US, "%.1f", value)

    /**
     * Before the first successful in-app sample, use a conservative estimate.
     * A successful local calibration always takes precedence over this estimate.
     */
    private fun fallbackFrequencyHz(context: Context): Float {
        val targetSdk = context.applicationInfo.targetSdkVersion
        val usesLegacySampling =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                targetSdk <= Build.VERSION_CODES.Q

        val estimatedSampleIntervalMs = if (usesLegacySampling) {
            100f
        } else {
            1_000f / readDisplayRefreshRate(context)
        }

        val estimate = (1_000f / (estimatedSampleIntervalMs * SAMPLES_PER_OFFSET_CYCLE)) *
            SAFETY_FACTOR
        return min(estimate, MAX_RECOMMENDED_FREQUENCY_HZ)
            .coerceIn(MIN_RECOMMENDED_FREQUENCY_HZ, MAX_RECOMMENDED_FREQUENCY_HZ)
    }

    @Suppress("DEPRECATION")
    private fun readDisplayRefreshRate(context: Context): Float {
        val refresh = runCatching {
            val manager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            manager?.defaultDisplay?.refreshRate
        }.getOrNull()

        return refresh
            ?.takeIf { it.isFinite() && it > 0f }
            ?: 60f
    }

    private fun preferencePrefix(context: Context): String {
        val model = "${Build.MANUFACTURER}_${Build.MODEL}"
            .lowercase(java.util.Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
        val targetSdk = context.applicationInfo.targetSdkVersion
        val refreshRate = readDisplayRefreshRate(context).roundToInt()
        return "${PREF_KEY_PREFIX}_${model}_api${Build.VERSION.SDK_INT}_target${targetSdk}_display${refreshRate}hz"
    }

    private fun percentile(sortedValues: List<Int>, fraction: Double): Int {
        if (sortedValues.isEmpty()) return 0
        val index = ceil((sortedValues.size - 1) * fraction).toInt()
            .coerceIn(0, sortedValues.lastIndex)
        return sortedValues[index]
    }

    private fun invalidResult(message: String) = GestureCalibrationResult(
        valid = false,
        message = message,
        recommendedFrequencyHz = 0f,
        medianIntervalMs = 0,
        p85IntervalMs = 0,
        jitterPercent = 0,
        intervalCount = 0,
        completedTrialCount = 0
    )
}
