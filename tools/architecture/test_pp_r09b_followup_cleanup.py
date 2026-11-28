"""R09-B1/B2/B3: exact deletion/extraction evidence and no retired references."""
import hashlib
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OLD_NEW = {
  "app/src/main/java/com/freedom/adapters/accessibility/android/MonitorOverlay.kt": [
    "9c88ed19eddca26138fad43d6a852f8df3a1de44",
    "1e4ff5d38f5c2b0433b81352cf9f0c8d031d6c66"
  ],
  "app/src/main/java/com/freedom/application/ports/MonitorDataPorts.kt": [
    "509ea931ee65ee7994515aaff785d02ce7ff4102",
    "93624f30c8026b53eefba63a3591fcf4e959c71a"
  ],
  "app/src/main/java/com/freedom/adapters/ai/DeepSeekAiModelTransport.kt": [
    "078a09b43ad3268983c140501f91b6cbeb727291",
    "8af8c4772b2415924e64d63cdd47a5e37bb5dea8"
  ],
  "app/src/main/java/com/freedom/adapters/ai/OpenAiCompatibleAiModelTransport.kt": [
    "9007ab686f11b4717347706bed5675858e13a565",
    "70e73359219880554132f75f193749ac9d81288b"
  ]
}
CHANGES = {
  "app/src/main/java/com/freedom/adapters/accessibility/android/MonitorOverlay.kt": [
    [
      25,
      "import android.widget.PopupMenu\n",
      ""
    ],
    [
      618,
      "    private fun requestGlobalNodeHierarchy(context: Context) {\n",
      ""
    ],
    [
      619,
      "        val app = context.applicationContext\n",
      ""
    ],
    [
      620,
      "        if (!isFreedomAccessibilityEnabled(app)) {\n",
      ""
    ],
    [
      621,
      "            Toast.makeText(app, \"请先启用 Freedom 无障碍服务，再查看节点层级\", Toast.LENGTH_LONG).show()\n",
      ""
    ],
    [
      622,
      "            return\n",
      ""
    ],
    [
      623,
      "        }\n",
      ""
    ],
    [
      624,
      "        runCatching {\n",
      ""
    ],
    [
      625,
      "            app.sendBroadcast(\n",
      ""
    ],
    [
      626,
      "                Intent(MonitorActions.ACTION_SHOW_GLOBAL_NODE_HIERARCHY).setPackage(app.packageName)\n",
      ""
    ],
    [
      627,
      "            )\n",
      ""
    ],
    [
      628,
      "        }.onFailure { error ->\n",
      ""
    ],
    [
      629,
      "            log(app, \"节点层级视图请求发送失败：${error.javaClass.simpleName}\")\n",
      ""
    ],
    [
      630,
      "        }\n",
      ""
    ],
    [
      631,
      "    }\n",
      ""
    ],
    [
      632,
      "\n",
      ""
    ],
    [
      633,
      "    private fun showNodeMoreMenu(context: Context, anchor: View) {\n",
      ""
    ],
    [
      634,
      "        PopupMenu(context, anchor).apply {\n",
      ""
    ],
    [
      635,
      "            menu.add(\"查看节点层级\").setOnMenuItemClickListener {\n",
      ""
    ],
    [
      636,
      "                collapseMenu()\n",
      ""
    ],
    [
      637,
      "                requestGlobalNodeHierarchy(context)\n",
      ""
    ],
    [
      638,
      "                true\n",
      ""
    ],
    [
      639,
      "            }\n",
      ""
    ],
    [
      640,
      "            menu.add(\"清除节点边界\").setOnMenuItemClickListener {\n",
      ""
    ],
    [
      641,
      "                collapseMenu()\n",
      ""
    ],
    [
      642,
      "                clearGlobalNodeLayoutBounds(context)\n",
      ""
    ],
    [
      643,
      "                true\n",
      ""
    ],
    [
      644,
      "            }\n",
      ""
    ],
    [
      645,
      "            menu.add(\"收起菜单\").setOnMenuItemClickListener {\n",
      ""
    ],
    [
      646,
      "                collapseMenu()\n",
      ""
    ],
    [
      647,
      "                true\n",
      ""
    ],
    [
      648,
      "            }\n",
      ""
    ],
    [
      649,
      "            show()\n",
      ""
    ],
    [
      650,
      "        }\n",
      ""
    ],
    [
      651,
      "    }\n",
      ""
    ],
    [
      652,
      "\n",
      ""
    ],
    [
      673,
      "    private fun clearGlobalNodeLayoutBounds(context: Context) {\n",
      ""
    ],
    [
      674,
      "        val app = context.applicationContext\n",
      ""
    ],
    [
      675,
      "        if (!isFreedomAccessibilityEnabled(app)) {\n",
      ""
    ],
    [
      676,
      "            Toast.makeText(app, \"Freedom 无障碍服务未启用，无法清除节点边界层\", Toast.LENGTH_LONG).show()\n",
      ""
    ],
    [
      677,
      "            return\n",
      ""
    ],
    [
      678,
      "        }\n",
      ""
    ],
    [
      679,
      "        runCatching {\n",
      ""
    ],
    [
      680,
      "            app.sendBroadcast(\n",
      ""
    ],
    [
      681,
      "                Intent(MonitorActions.ACTION_CLEAR_GLOBAL_NODE_LAYOUT_BOUNDS).setPackage(app.packageName)\n",
      ""
    ],
    [
      682,
      "            )\n",
      ""
    ],
    [
      683,
      "        }.onSuccess {\n",
      ""
    ],
    [
      684,
      "            log(app, \"已从悬浮按钮请求清除目标 App 节点边界层\")\n",
      ""
    ],
    [
      685,
      "        }.onFailure { error ->\n",
      ""
    ],
    [
      686,
      "            log(app, \"清除节点边界请求发送失败：${error.javaClass.simpleName}\")\n",
      ""
    ],
    [
      687,
      "        }\n",
      ""
    ],
    [
      688,
      "    }\n",
      ""
    ],
    [
      689,
      "\n",
      ""
    ]
  ],
  "app/src/main/java/com/freedom/application/ports/MonitorDataPorts.kt": [
    [
      2,
      "/** Read/write boundary for target selection and the existing monitor presentation snapshot. */\n",
      ""
    ],
    [
      3,
      "interface MonitorSettingsRepository {\n",
      ""
    ],
    [
      4,
      "    fun targetPackage(): String\n",
      ""
    ],
    [
      5,
      "    fun setTargetPackage(packageName: String)\n",
      ""
    ],
    [
      6,
      "}\n",
      ""
    ],
    [
      7,
      "\n",
      ""
    ]
  ],
  "app/src/main/java/com/freedom/adapters/ai/DeepSeekAiModelTransport.kt": [
    [
      11,
      "import java.io.ByteArrayOutputStream\n",
      ""
    ],
    [
      35,
      "        val endpoint = chatCompletionsEndpoint(config.apiBaseUrl)\n",
      "        val endpoint = AiHttpTransportSupport.chatCompletionsEndpoint(config.apiBaseUrl, CHAT_COMPLETIONS_PATH)\n"
    ],
    [
      63,
      "                    trace.captureProviderError(String(readBounded(it, MAX_RESPONSE_BYTES), StandardCharsets.UTF_8))\n",
      "                    trace.captureProviderError(String(AiHttpTransportSupport.readBounded(it, MAX_RESPONSE_BYTES), StandardCharsets.UTF_8))\n"
    ],
    [
      65,
      "                throw httpFailure(status)\n",
      "                throw AiHttpTransportSupport.httpFailure(status)\n"
    ],
    [
      69,
      "                String(readBounded(it, MAX_RESPONSE_BYTES), StandardCharsets.UTF_8)\n",
      "                String(AiHttpTransportSupport.readBounded(it, MAX_RESPONSE_BYTES), StandardCharsets.UTF_8)\n"
    ],
    [
      87,
      "    private fun chatCompletionsEndpoint(baseUrl: String): String {\n",
      ""
    ],
    [
      88,
      "        val normalized = baseUrl.trim().trimEnd('/')\n",
      ""
    ],
    [
      89,
      "        require(normalized.startsWith(\"https://\", ignoreCase = true)) { \"HTTPS_REQUIRED\" }\n",
      ""
    ],
    [
      90,
      "        return if (normalized.endsWith(CHAT_COMPLETIONS_PATH, ignoreCase = true)) {\n",
      ""
    ],
    [
      91,
      "            normalized\n",
      ""
    ],
    [
      92,
      "        } else {\n",
      ""
    ],
    [
      93,
      "            \"$normalized$CHAT_COMPLETIONS_PATH\"\n",
      ""
    ],
    [
      94,
      "        }\n",
      ""
    ],
    [
      95,
      "    }\n",
      ""
    ],
    [
      96,
      "\n",
      ""
    ],
    [
      221,
      "    private fun httpFailure(status: Int): AiModelHttpException = when (status) {\n",
      ""
    ],
    [
      222,
      "        401 -> AiModelHttpException(\"HTTP_401_UNAUTHORIZED\")\n",
      ""
    ],
    [
      223,
      "        403 -> AiModelHttpException(\"HTTP_403_FORBIDDEN\")\n",
      ""
    ],
    [
      224,
      "        404 -> AiModelHttpException(\"HTTP_404_NOT_FOUND\")\n",
      ""
    ],
    [
      225,
      "        408 -> AiModelHttpException(\"HTTP_408_TIMEOUT\", retryable = true)\n",
      ""
    ],
    [
      226,
      "        413 -> AiModelHttpException(\"HTTP_413_REQUEST_TOO_LARGE\")\n",
      ""
    ],
    [
      227,
      "        429 -> AiModelHttpException(\"HTTP_429_RATE_LIMITED\", retryable = true)\n",
      ""
    ],
    [
      228,
      "        in 500..599 -> AiModelHttpException(\"HTTP_5XX_SERVER_ERROR\", retryable = true)\n",
      ""
    ],
    [
      229,
      "        else -> AiModelHttpException(\"HTTP_$status\")\n",
      ""
    ],
    [
      230,
      "    }\n",
      ""
    ],
    [
      231,
      "\n",
      ""
    ],
    [
      232,
      "    private fun readBounded(input: java.io.InputStream, maxBytes: Int): ByteArray {\n",
      ""
    ],
    [
      233,
      "        val output = ByteArrayOutputStream(minOf(maxBytes, 8_192))\n",
      ""
    ],
    [
      234,
      "        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)\n",
      ""
    ],
    [
      235,
      "        var total = 0\n",
      ""
    ],
    [
      236,
      "        while (true) {\n",
      ""
    ],
    [
      237,
      "            val count = input.read(buffer)\n",
      ""
    ],
    [
      238,
      "            if (count < 0) break\n",
      ""
    ],
    [
      239,
      "            total += count\n",
      ""
    ],
    [
      240,
      "            if (total > maxBytes) throw AiModelHttpException(\"HTTP_RESPONSE_TOO_LARGE\")\n",
      ""
    ],
    [
      241,
      "            output.write(buffer, 0, count)\n",
      ""
    ],
    [
      242,
      "        }\n",
      ""
    ],
    [
      243,
      "        return output.toByteArray()\n",
      ""
    ],
    [
      244,
      "    }\n",
      ""
    ],
    [
      245,
      "\n",
      ""
    ]
  ],
  "app/src/main/java/com/freedom/adapters/ai/OpenAiCompatibleAiModelTransport.kt": [
    [
      10,
      "import java.io.ByteArrayOutputStream\n",
      ""
    ],
    [
      177,
      "        val endpoint = chatCompletionsEndpoint(config.apiBaseUrl)\n",
      "        val endpoint = AiHttpTransportSupport.chatCompletionsEndpoint(config.apiBaseUrl, CHAT_COMPLETIONS_PATH)\n"
    ],
    [
      206,
      "                    trace.captureProviderError(String(readBounded(it, MAX_RESPONSE_BYTES), StandardCharsets.UTF_8))\n",
      "                    trace.captureProviderError(String(AiHttpTransportSupport.readBounded(it, MAX_RESPONSE_BYTES), StandardCharsets.UTF_8))\n"
    ],
    [
      208,
      "                throw httpFailure(status)\n",
      "                throw AiHttpTransportSupport.httpFailure(status)\n"
    ],
    [
      212,
      "                String(readBounded(it, MAX_RESPONSE_BYTES), StandardCharsets.UTF_8)\n",
      "                String(AiHttpTransportSupport.readBounded(it, MAX_RESPONSE_BYTES), StandardCharsets.UTF_8)\n"
    ],
    [
      230,
      "    private fun chatCompletionsEndpoint(baseUrl: String): String {\n",
      ""
    ],
    [
      231,
      "        val normalized = baseUrl.trim().trimEnd('/')\n",
      ""
    ],
    [
      232,
      "        require(normalized.startsWith(\"https://\", ignoreCase = true)) { \"HTTPS_REQUIRED\" }\n",
      ""
    ],
    [
      233,
      "        return if (normalized.endsWith(CHAT_COMPLETIONS_PATH, ignoreCase = true)) {\n",
      ""
    ],
    [
      234,
      "            normalized\n",
      ""
    ],
    [
      235,
      "        } else {\n",
      ""
    ],
    [
      236,
      "            \"$normalized$CHAT_COMPLETIONS_PATH\"\n",
      ""
    ],
    [
      237,
      "        }\n",
      ""
    ],
    [
      238,
      "    }\n",
      ""
    ],
    [
      239,
      "\n",
      ""
    ],
    [
      311,
      "    private fun httpFailure(status: Int): AiModelHttpException = when (status) {\n",
      ""
    ],
    [
      312,
      "        401 -> AiModelHttpException(\"HTTP_401_UNAUTHORIZED\")\n",
      ""
    ],
    [
      313,
      "        403 -> AiModelHttpException(\"HTTP_403_FORBIDDEN\")\n",
      ""
    ],
    [
      314,
      "        404 -> AiModelHttpException(\"HTTP_404_NOT_FOUND\")\n",
      ""
    ],
    [
      315,
      "        408 -> AiModelHttpException(\"HTTP_408_TIMEOUT\", retryable = true)\n",
      ""
    ],
    [
      316,
      "        413 -> AiModelHttpException(\"HTTP_413_REQUEST_TOO_LARGE\")\n",
      ""
    ],
    [
      317,
      "        429 -> AiModelHttpException(\"HTTP_429_RATE_LIMITED\", retryable = true)\n",
      ""
    ],
    [
      318,
      "        in 500..599 -> AiModelHttpException(\"HTTP_5XX_SERVER_ERROR\", retryable = true)\n",
      ""
    ],
    [
      319,
      "        else -> AiModelHttpException(\"HTTP_$status\")\n",
      ""
    ],
    [
      320,
      "    }\n",
      ""
    ],
    [
      321,
      "\n",
      ""
    ],
    [
      322,
      "    private fun readBounded(input: java.io.InputStream, maxBytes: Int): ByteArray {\n",
      ""
    ],
    [
      323,
      "        val output = ByteArrayOutputStream(minOf(maxBytes, 8_192))\n",
      ""
    ],
    [
      324,
      "        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)\n",
      ""
    ],
    [
      325,
      "        var total = 0\n",
      ""
    ],
    [
      326,
      "        while (true) {\n",
      ""
    ],
    [
      327,
      "            val count = input.read(buffer)\n",
      ""
    ],
    [
      328,
      "            if (count < 0) break\n",
      ""
    ],
    [
      329,
      "            total += count\n",
      ""
    ],
    [
      330,
      "            if (total > maxBytes) throw AiModelHttpException(\"HTTP_RESPONSE_TOO_LARGE\")\n",
      ""
    ],
    [
      331,
      "            output.write(buffer, 0, count)\n",
      ""
    ],
    [
      332,
      "        }\n",
      ""
    ],
    [
      333,
      "        return output.toByteArray()\n",
      ""
    ],
    [
      334,
      "    }\n",
      ""
    ],
    [
      335,
      "\n",
      ""
    ]
  ]
}
HELPER = "app/src/main/java/com/freedom/adapters/ai/AiHttpTransportSupport.kt"
HELPER_SHA = "4f554b193371269082187a5fc2bf7744c86effeb"
MODULES = ("app", "business", "core", "gesture/gesture-api", "gesture/gesture-core",
           "gesture/gesture-runtime", "gesture/gesture-android", "ocr-plugin-api", "ocr-plugin")

def blob(source):
    data = source.encode("utf-8")
    return hashlib.sha1(("blob %d\0" % len(data)).encode("ascii") + data).hexdigest()

def restore(path, source):
    lines = source.splitlines(keepends=True)
    for index, previous, replacement in CHANGES[path]:
        if replacement:
            if index >= len(lines) or lines[index] != replacement:
                raise AssertionError("R09-B call-site inverse anchor changed")
            lines[index] = previous
        else:
            lines.insert(index, previous)
    result = "".join(lines)
    if blob(result) != OLD_NEW[path][0]:
        raise AssertionError("R09-B original source reconstruction failed: " + path)
    return result

class PpR09bFollowupCleanupTest(unittest.TestCase):
    def test_exact_inverse_and_shared_transport(self):
        for path, (previous, current) in OLD_NEW.items():
            source = (ROOT / path).read_text(encoding="utf-8")
            self.assertEqual(current, blob(source), path)
            self.assertEqual(previous, blob(restore(path, source)), path)
            with self.assertRaises(AssertionError):
                restore(path, source + "\n// unexpected change\n")
            if path.endswith("AiModelTransport.kt"):
                self.assertEqual(1, source.count("AiHttpTransportSupport.chatCompletionsEndpoint("))
                self.assertEqual(2, source.count("AiHttpTransportSupport.readBounded("))
                self.assertEqual(1, source.count("AiHttpTransportSupport.httpFailure("))
                self.assertNotRegex(source, r"private fun (?:chatCompletionsEndpoint|readBounded|httpFailure)\(")
        helper = (ROOT / HELPER).read_text(encoding="utf-8")
        self.assertEqual(HELPER_SHA, blob(helper))
        for method in ("chatCompletionsEndpoint", "readBounded", "httpFailure"):
            self.assertEqual(1, len(re.findall(r"\bfun " + method + r"\(", helper)))

    def test_retired_symbols_and_live_overlay_entry(self):
        retired = re.compile(r"\b(?:showNodeMoreMenu|MonitorSettingsRepository)\b")
        issues = []
        for module in MODULES:
            directory = ROOT / module
            for path in list((directory / "src").rglob("*")) + list(directory.glob("*.pro")) + list(directory.glob("*.gradle.kts")):
                if path.is_file() and path.suffix in {".kt", ".java", ".xml", ".aidl", ".pro", ".kts", ".json", ".properties"}:
                    if retired.search(path.read_text(encoding="utf-8")):
                        issues.append(str(path.relative_to(ROOT)))
        self.assertEqual([], issues)
        overlay = (ROOT / "app/src/main/java/com/freedom/adapters/accessibility/android/MonitorOverlay.kt").read_text(encoding="utf-8")
        self.assertNotRegex(overlay, r"\b(?:PopupMenu|requestGlobalNodeHierarchy|clearGlobalNodeLayoutBounds)\b")
        self.assertIn("requestNodeBounds = ::requestGlobalNodeLayoutBounds", overlay)
        self.assertIn("ACTION_SHOW_GLOBAL_NODE_LAYOUT_BOUNDS", overlay)
        ports = (ROOT / "app/src/main/java/com/freedom/application/ports/MonitorDataPorts.kt").read_text(encoding="utf-8")
        self.assertIn("data class MonitorPresentationSnapshot", ports)
        self.assertIn("interface MonitorSnapshotRepository", ports)
