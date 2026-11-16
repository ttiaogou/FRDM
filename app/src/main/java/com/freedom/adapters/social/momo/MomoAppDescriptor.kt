package com.freedom.adapters.social.momo

/**
 * Momo-specific application identity. Keep package and Activity identifiers inside the
 * social adapter boundary so business features consume semantic evidence instead.
 */
internal object MomoAppDescriptor {
    const val PACKAGE_NAME = "com.immomo.momo"
    const val MAIN_ACTIVITY_CLASS = "com.immomo.momo.maintab.MaintabActivity"
    const val DYNAMIC_DETAIL_ACTIVITY_CLASS = "com.immomo.momo.luaview.LuaViewActivity"
    const val FEED_IMAGE_BROWSER_ACTIVITY_CLASS =
        "com.immomo.momo.imagefactory.imageborwser.impls.FeedImageBrowserActivity"
    const val CHAT_DETAIL_ACTIVITY_CLASS = "com.immomo.momo.message.activity.ChatActivity"
    const val CHAT_MESSAGE_LIST_VIEW_ID = "com.immomo.momo:id/message_chat_recycler_view"
    const val CHAT_MESSAGE_LIST_CLASS_NAME = "androidx.recyclerview.widget.RecyclerView"
}
