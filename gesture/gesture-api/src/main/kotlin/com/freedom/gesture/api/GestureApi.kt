package com.freedom.gesture.api

import com.freedom.core.accessibility.api.GestureSubmission
import com.freedom.core.accessibility.api.WindowRef
import com.freedom.core.accessibility.gesture.GestureCoordinateContext
import com.freedom.core.accessibility.gesture.GestureExecutionContext
import com.freedom.core.accessibility.gesture.GestureExecutionListener
import com.freedom.core.accessibility.gesture.GestureIntentRequest

/**
 * Freedom 的唯一公开手势入口。
 *
 * Feature 与 Adapter 只能通过此接口提交语义手势意图；物理轨迹与平台执行细节
 * 均由 Runtime 内部决定，不属于业务调用契约。
 * captureContext 只用于把目标窗口/显示快照绑定到请求，不会执行手势。
 */
interface GestureApi {
    /** 捕获并校验请求坐标所属的运行代次、窗口和显示环境。 */
    fun captureContext(
        expectedWindow: WindowRef? = null,
        expectedCoordinates: GestureCoordinateContext? = null
    ): GestureExecutionContext?

    /** 将业务意图提交给统一 Runtime；ACCEPTED 表示已接纳（可能正在执行，也可能等待 FIFO）；调用者不能指定物理轨迹或 Android 执行细节。 */
    fun execute(
        request: GestureIntentRequest,
        listener: GestureExecutionListener
    ): GestureSubmission

    /** 按请求 ID 撤销对应活动或排队手势，避免业务层获得原始执行器或全局取消能力。 */
    fun cancelActive(
        commandId: String,
        reason: String = "cancelled_by_caller"
    ): Boolean
}
