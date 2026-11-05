package com.suisheng.player.model

/**
 * 分组页（专辑墙 / 艺术家 / 文件夹）里的一格。
 *
 * 只有 [title] 与 [songs] 是本质数据；[subtitle] 由页面自己按模式格式化成
 * 「N 首 / N 张专辑」这类文案，因此这里不存资源 id —— 纯 Kotlin 才能进单元测试。
 *
 * @property key 稳定标识。专辑/艺术家用名字，文件夹用绝对路径；用于封面缓存与 DiffUtil。
 * @property title 显示名
 * @property songs 该组下的全部歌曲
 * @property subtitle 计数（歌曲数；文件夹模式下也是歌曲数，不是子文件夹数）
 */
data class LibraryGroup(
    val key: String,
    val title: String,
    val songs: List<Song>,
    val subtitle: Int = songs.size,
)
