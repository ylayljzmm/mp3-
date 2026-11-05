package com.suisheng.player.data

object PodcastDefaults {
    data class Feed(val title: String, val url: String)

    val feeds: List<Feed> = listOf(
        Feed("哈喽怪谈", "https://s2.proxy.wavpub.com/helloguaitan.xml"),
        Feed("悬疑案件·扑朔迷离", "https://s1.proxy.wavpub.com/yeelokxyaj.xml"),
        Feed("晓东讲故事·奇闻档案", "http://www.ximalaya.com/album/30327212.xml"),
        Feed("惊奇怪谈·都市传说", "https://www.ximalaya.com/album/85027733.xml"),
        Feed("惊天大案·案件纪实", "http://www.ximalaya.com/album/24729905.xml"),
        Feed("奇闻异事·民间杂谈", "http://www.ximalaya.com/album/61790644.xml"),
        Feed("灵异特辑·心跳故事", "https://s1.proxy.wavpub.com/yeeloklytj.xml"),
        Feed("不停·流行音乐", "http://www.ximalaya.com/album/253733.xml")
    )

    fun feedUrls(): List<String> = feeds.map { it.url }

    fun defaultRssText(): String = feedUrls().joinToString("\n")

    fun displayName(url: String): String? =
        feeds.firstOrNull { it.url.equals(url.trim(), ignoreCase = true) }?.title
}
