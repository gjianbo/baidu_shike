package com.hnwd.bdplayer

import org.json.JSONObject

internal data class DrivePage<T>(val items: List<T>, val hasMore: Boolean)

internal class DriveRead<T>(val url: String, val decode: (JSONObject) -> DrivePage<T>)

internal interface PersonalDriveProtocol<File, Hit> {
    fun list(directory: String, page: Int, size: Int): DriveRead<File>
    fun search(keyword: String, page: Int, size: Int): DriveRead<Hit>
}

internal class BaiduPersonalDrive(private val token: String) : PersonalDriveProtocol<PanEntry, PanSearchHit> {
    override fun list(directory: String, page: Int, size: Int) =
        DriveRead(PanApi.diskList(token, directory, page, size)) { json ->
            DrivePage(PanApi.parseDiskList(json), PanApi.diskHasMore(json, size))
        }

    override fun search(keyword: String, page: Int, size: Int) =
        DriveRead(PanApi.diskSearch(token, keyword, page = page, num = size)) { json ->
            val hits = PanApi.parseDiskSearch(json)
            DrivePage(hits, hits.isNotEmpty() && PanApi.diskHasMore(json, size))
        }
}
