package com.imcys.bilibilias.download

import android.app.Application
import com.imcys.bilibilias.common.utils.download.CCJsonToAss
import com.imcys.bilibilias.common.utils.download.CCJsonToSrt
import com.imcys.bilibilias.common.utils.toHttps
import com.imcys.bilibilias.data.model.download.CCFileType
import com.imcys.bilibilias.data.model.download.lowercase
import com.imcys.bilibilias.data.repository.VideoInfoRepository
import com.imcys.bilibilias.database.entity.download.NamingConventionInfo
import com.imcys.bilibilias.network.NetWorkResult
import com.imcys.bilibilias.network.model.video.BILIVideoCCInfo
import com.imcys.bilibilias.network.model.video.BILIVideoPlayerInfoV2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 字幕下载器
 * 负责字幕下载、转换和保存
 */
class SubtitleDownloader(
    private val videoInfoRepository: VideoInfoRepository,
    private val fileOutputManager: FileOutputManager,
    private val context: Application,
    private val namingConventionHandler: NamingConventionHandler
) {
    /**
     * 下载字幕用于嵌入视频
     */
    suspend fun downloadSubtitlesForEmbed(
        videoPlayerInfoV2: NetWorkResult<BILIVideoPlayerInfoV2?>,
        segmentId: Long
    ): List<LocalSubtitle> = withContext(Dispatchers.IO) {
        val localSubtitles = mutableListOf<LocalSubtitle>()

        videoPlayerInfoV2.data?.subtitle?.subtitles?.forEach { subtitle ->
            val url = subtitle.finalSubtitleUrl
            val finalUrl = if (!url.contains("https")) "https:" else ""
            val language = subtitle.lan
            val langDoc = subtitle.lanDoc

            runCatching {
                videoInfoRepository.getVideoCCInfo((finalUrl + url).toHttps())
            }.onSuccess { ccInfo ->
                val fileContentStr = CCJsonToSrt.jsonToSrt(ccInfo)
                val tempDir = File(context.externalCacheDir, "cc")
                if (!tempDir.exists()) tempDir.mkdirs()

                val tempFile = File(tempDir, "embed_cc_${segmentId}_${language}.srt")
                FileOutputStream(tempFile).use { outputStream ->
                    outputStream.write(fileContentStr.toByteArray(Charsets.UTF_8))
                    outputStream.flush()
                }

                localSubtitles.add(LocalSubtitle(language, langDoc, tempFile.absolutePath))
            }
        }

        localSubtitles
    }

    /**
     * 下载字幕到下载目录
     * @param videoPlayerInfoV2 视频播放信息
     * @param namingConventionInfo 命名规则信息
     * @param ccFileType 字幕文件类型
     */
    suspend fun downloadSubtitlesToFile(
        videoPlayerInfoV2: NetWorkResult<BILIVideoPlayerInfoV2?>,
        namingConventionInfo: NamingConventionInfo?,
        ccFileType: CCFileType
    ) = withContext(Dispatchers.IO) {
        videoPlayerInfoV2.data?.subtitle?.subtitles?.forEach { cc ->
            val url = cc.finalSubtitleUrl
            val finalUrl = if (!url.contains("https")) "https:" else ""
            val videoCCInfo = videoInfoRepository.getVideoCCInfo((finalUrl + url).toHttps())
            val content = convertCc(videoCCInfo, ccFileType)

            // 使用命名规则生成文件名，与视频文件保持一致
            val subtitleExtension = when (ccFileType) {
                CCFileType.ASS -> "ass"
                CCFileType.SRT -> "srt"
            }
            val baseFileName = namingConventionHandler.buildFileName(namingConventionInfo, subtitleExtension)
            // 移除视频文件后缀，替换为字幕后缀
            val fileName = baseFileName.substringBeforeLast(".") + "_${cc.lan}." + subtitleExtension

            val subtitleType = when (ccFileType) {
                CCFileType.ASS -> FileOutputManager.SubtitleType.ASS
                CCFileType.SRT -> FileOutputManager.SubtitleType.SRT
            }

            fileOutputManager.createSubtitleOutputStream(fileName, subtitleType)
                .use { it.write(content.toByteArray(Charsets.UTF_8)) }
        }
    }

    private fun convertCc(cc: BILIVideoCCInfo, type: CCFileType): String = when (type) {
        CCFileType.ASS -> CCJsonToAss.jsonToAss(cc, "字幕", "1920", "1080")
        CCFileType.SRT -> CCJsonToSrt.jsonToSrt(cc)
    }
}
