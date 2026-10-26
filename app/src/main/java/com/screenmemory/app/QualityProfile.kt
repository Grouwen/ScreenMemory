package com.screenmemory.app

/**
 * 画质档位：一次决定「录制分辨率 + 码率 + 抽帧尺寸 + JPEG 质量」。
 *
 * 这四项是绑在一起的：录制分辨率决定了抽帧的像素上限，码率决定解码后画面有多少噪点，
 * 抽帧尺寸和 JPEG 质量决定第二次压缩的损失。拆开让用户逐个调，很容易配出
 * 「1080p 录制 + q50 抽帧」这种自相矛盾的组合，所以做成档位。
 *
 * 尺寸一律按「短边」衡量，和常说的 720p / 1080p 一致。竖屏手机的长边是高度，
 * 拿最长边当基准会把 1080 宽的屏幕压成 480 宽（20:9 机型），中文小字直接糊掉。
 */
enum class QualityProfile(
    /** 录制时屏幕短边压到多少像素（即常说的 720p / 1080p） */
    val shortSide: Int,
    /** H.264 目标码率（bps） */
    val videoBitRate: Int,
    /** 抽帧时短边缩到多少像素；只缩不放，也不会超过录制尺寸 */
    val frameShortSide: Int,
    /** 抽帧 JPEG 质量 */
    val frameQuality: Int
) {
    /**
     * 省流量：给「视频直传 + 短窗口」用。
     *
     * 录的是屏幕内容（大片纯色 + 文字），熵远低于实拍，1.2 Mbps 在 720 短边下
     * 讲稿、PPT、板书都还清楚；实测硬编码器即便 CBR 也会有些许超发，
     * 所以目标值特意留了余量。
     */
    SAVER(720, 1_200_000, 720, 65),

    /** 标准：1080p 录制 + q80 抽帧，小字能认清 */
    STANDARD(1080, 6_000_000, 1080, 80),

    /** 清晰：码率翻倍，压缩噪点最少，适合表格/代码/小字 */
    SHARP(1080, 10_000_000, 1080, 90);

    /** 30 秒片段的大致体积（MB） */
    fun estimatedSizeMb(seconds: Int): Int =
        (videoBitRate.toLong() * seconds / 8 / 1024 / 1024).toInt()

    fun summary(seconds: Int): String =
        "短边 ${shortSide}px · ${"%.1f".format(videoBitRate / 1_000_000f)}Mbps · " +
            "抽帧 ${frameShortSide}px/q$frameQuality · ${seconds} 秒约 ${estimatedSizeMb(seconds)}MB"

    companion object {
        const val DEFAULT_INDEX = 1

        fun of(index: Int): QualityProfile = values().getOrElse(index) { STANDARD }
    }
}
