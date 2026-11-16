/*
 * Ported from AutoJs6 common-plugin-api (MPL-2.0).
 * Upstream: https://github.com/SuperMonster003/AutoJs6
 * Revision: b95b98b339c56ab12ba20b279d2b998eb6e2778c
 * Adaptation: retained the upstream parcelable wire contract; Freedom uses its own
 * host-version compatibility policy and signature permission.
 */
package org.autojs.plugin.common.api

import android.os.Bundle
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
class PluginInfo(
    var name: String,
    var description: String?,
    var instruction: String?,
    var author: String,
    var collaborators: Array<String>?,
    var versionName: String,
    var versionCode: Long,
    var versionDate: String?,
    var id: String?,
    var engine: String?,
    var variant: String?,
    var supportedAbis: Array<String>?,
    var capabilities: Bundle?,
) : Parcelable {
    constructor() : this(
        name = "",
        description = null,
        instruction = null,
        author = "",
        collaborators = null,
        versionName = "",
        versionCode = 0L,
        versionDate = null,
        id = null,
        engine = null,
        variant = null,
        supportedAbis = null,
        capabilities = null,
    )
}
