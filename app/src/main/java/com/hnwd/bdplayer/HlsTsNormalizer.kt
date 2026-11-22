package com.hnwd.bdplayer

/**
 * HLS 分片拼接前的 MPEG-TS 归一化：把各 PID 的 continuity_counter 接成一条连续流。
 *
 * 背景（2026-11-22 真机产物 ffprobe / ffmpeg 实测，见
 * `docs/百度群组视频下载失败-分析与修复.md`）：`/mbox/msg/streaming` 下发的每个
 * TS 分片都是一段**独立可播的流** —— 开头自带 PAT / PMT / SDT，且所有 PID 的
 * continuity_counter 从 0 重新计数。旧实现把分片字节直接首尾相接，播放器在
 * 76 个分片边界各检测到一次 CC discontinuity，判定丢包、把尚未结束的 PES
 * 标记 corrupt 并重新同步音视频时钟，听感就是「画面和声音不同步」。
 *
 * 同一份 31 MB / 660 s 产物的对照实验：
 * - 裸拼接：ffmpeg 报 76 个 `Packet corrupt (stream = 0)`（恰好等于分片边界数）；
 * - **只把 CC 全局连续化：0 个**，16517 视频帧 / 30972 音频帧、时长、字节数全不变；
 * - 只剔除重复 PAT / PMT：仍是 76 个 → 重复 PSI 表在连续 TS 里合法，不是根因。
 *
 * 所以这里只改每个 TS 包第 4 字节的低 4 位：不重封装、不重编码、不删字节、
 * 不挑 PSI 表，也不假设 PMT 的 PID。PCR / PTS / PES 数据一律原样保留。
 * 有状态（跨分片累计），一个下载任务用一个实例；fMP4 分片不能走这里。
 */
internal class HlsTsNormalizer {
    private val lastCc = HashMap<Int, Int>()

    /**
     * 就地把一个分片的 CC 接到上一片之后。
     *
     * 输入必须是 188 字节对齐的标准 TS 分片；长度不足、或任一完整包的同步字节
     * 不是 0x47 时**整片原样放过**，避免把不认识的容器改坏（也避免只改一半导致
     * CC 半新半旧）。尾部的非整包残字节保留不动。
     */
    fun normalize(segment: ByteArray) {
        if (!isStandardTs(segment)) return
        var offset = 0
        while (offset + PACKET_SIZE <= segment.size) {
            val pid = ((segment[offset + 1].toInt() and 0x1F) shl 8) or
                (segment[offset + 2].toInt() and 0xFF)
            val header = segment[offset + 3].toInt() and 0xFF
            val afc = (header shr 4) and 0x3
            if (pid != NULL_PID) {
                val next = when (afc) {
                    // 只有 adaptation field（或 reserved）：CC 不递增，沿用上一次的值。
                    // 这里不能 +1，否则 PCR 所在的空包会把计数顶掉。
                    0, 2 -> lastCc[pid] ?: (header and 0x0F)
                    // 有 payload：按 PID 各自 +1，4 bit 回绕。
                    1, 3 -> ((lastCc[pid] ?: 15) + 1) and 0x0F
                    else -> header and 0x0F
                }
                lastCc[pid] = next
                segment[offset + 3] = ((header and 0xF0) or next).toByte()
            }
            offset += PACKET_SIZE
        }
    }

    private fun isStandardTs(segment: ByteArray): Boolean {
        if (segment.size < PACKET_SIZE) return false
        var offset = 0
        while (offset + PACKET_SIZE <= segment.size) {
            if (segment[offset].toInt() and 0xFF != SYNC_BYTE) return false
            offset += PACKET_SIZE
        }
        return true
    }
}

private const val PACKET_SIZE = 188
private const val SYNC_BYTE = 0x47
private const val NULL_PID = 0x1FFF
