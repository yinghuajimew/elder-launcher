package com.elder.launcher.player

import org.json.JSONArray
import org.json.JSONObject

/** 视频类型：本地文件 / 网络 URL。 */
enum class VideoType { LOCAL, NETWORK }

/** 播放列表排序方式。 */
enum class SortMode { ADD_ORDER, NAME_AZ, NAME_ZA, MANUAL }

/** 播放列表中的单个视频。 */
data class VideoEntry(
    val uri: String,
    val name: String,
    val type: VideoType = VideoType.LOCAL
)

/** 播放列表的 JSON 编解码（供桌面磁贴存储与播放器传递）。 */
object Playlist {

    fun encode(entries: List<VideoEntry>): String {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(
                JSONObject()
                    .put("u", e.uri)
                    .put("n", e.name)
                    .put("t", e.type.name)
            )
        }
        return arr.toString()
    }

    fun decode(json: String): List<VideoEntry> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            VideoEntry(
                o.getString("u"),
                o.optString("n", ""),
                try {
                    VideoType.valueOf(o.optString("t", "LOCAL"))
                } catch (_: Exception) {
                    VideoType.LOCAL
                }
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    /** 按指定排序方式返回排序后的新列表（不修改原列表）。 */
    fun sorted(entries: List<VideoEntry>, mode: SortMode, manualOrder: String = ""): List<VideoEntry> {
        return when (mode) {
            SortMode.ADD_ORDER -> entries
            SortMode.NAME_AZ -> entries.sortedBy { it.name.lowercase() }
            SortMode.NAME_ZA -> entries.sortedByDescending { it.name.lowercase() }
            SortMode.MANUAL -> {
                val orderList = manualOrder.split(",").filter { it.isNotEmpty() }
                if (orderList.isEmpty()) return entries
                val ordered = mutableListOf<VideoEntry>()
                val remaining = entries.toMutableList()
                for (key in orderList) {
                    val found = remaining.firstOrNull { it.uri == key }
                    if (found != null) {
                        ordered.add(found)
                        remaining.remove(found)
                    }
                }
                ordered.addAll(remaining)
                ordered
            }
        }
    }
}
