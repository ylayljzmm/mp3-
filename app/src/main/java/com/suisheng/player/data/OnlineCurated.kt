package com.suisheng.player.data

import com.suisheng.player.model.CuratedChart
import com.suisheng.player.model.CuratedPlaylist

object OnlineCurated {
    val charts: List<CuratedChart> = listOf(
        CuratedChart("3778678", "热歌榜"),
        CuratedChart("3779629", "新歌榜"),
        CuratedChart("19723756", "飙升榜"),
        CuratedChart("2884035", "原创榜")
    )

    /** 已验证可用的公开歌单（GD Studio types=playlist 可正常加载） */
    val featuredPlaylists: List<CuratedPlaylist> = listOf(
        CuratedPlaylist("13820356968", "90后华语回忆录", "218 首经典"),
        CuratedPlaylist("5347332390", "8090后青春记忆", "832 首怀旧"),
        CuratedPlaylist("621935756", "周杰伦热门歌曲", "184 首 Jay Chou"),
        CuratedPlaylist("7694176632", "林俊杰精选", "40 首 JJ Lin"),
        CuratedPlaylist("532892827", "王力宏精选", "50 首 Leehom"),
        CuratedPlaylist("14060780288", "陶喆 R&B 精选", "79 首 David Tao"),
        CuratedPlaylist("13532313680", "华语 R&B 慢歌", "80 首节奏蓝调"),
        CuratedPlaylist("2829883282", "华语私人雷达", "每日更新"),
        CuratedPlaylist("17747519380", "经典老歌精选", "100 首金曲"),
        CuratedPlaylist("14069098211", "开车必听华语", "427 首 KTV 经典")
    )
}
