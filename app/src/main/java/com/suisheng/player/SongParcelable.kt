package com.suisheng.player

import android.os.Parcel
import android.os.Parcelable
import com.suisheng.player.model.Song

/**
 * 跨进程 / 跨组件传 [Song] 的 Parcelable 形态。
 *
 * ## 5.8.0：补了 `album` / `year`
 *
 * 之前只带 6 个字段，于是「队列撤销」把歌插回去之后专辑名丢了 ——
 * 队列行还是那首歌，封面和专辑文案却退化成「未知专辑」。这不是新引入的坑，
 * 而是旧字段表一直少两项，只是以前没有「把一首歌重新塞回队列」的路径去暴露它。
 *
 * 追加字段放在末尾，写入顺序与读取顺序一一对应；这个类型只在**同一份 APK 内**
 * 通过 Intent extra 传递，不存在跨版本反序列化，所以不需要写版本号。
 */
data class SongParcelable(
    val id: Long,
    val title: String,
    val artist: String,
    val path: String,
    val lrcPath: String?,
    val durationMs: Long = 0L,
    val album: String = "",
    val year: Int = 0,
) : Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readLong(),
        parcel.readString() ?: "",
        parcel.readString() ?: "",
        parcel.readString() ?: "",
        parcel.readString(),
        parcel.readLong(),
        parcel.readString() ?: "",
        parcel.readInt(),
    )

    fun toSong() = Song(id, title, artist, path, lrcPath, durationMs, album, year)

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeLong(id)
        parcel.writeString(title)
        parcel.writeString(artist)
        parcel.writeString(path)
        parcel.writeString(lrcPath)
        parcel.writeLong(durationMs)
        parcel.writeString(album)
        parcel.writeInt(year)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<SongParcelable> {
        override fun createFromParcel(parcel: Parcel): SongParcelable = SongParcelable(parcel)
        override fun newArray(size: Int): Array<SongParcelable?> = arrayOfNulls(size)

        fun from(song: Song) = SongParcelable(
            song.id, song.title, song.artist, song.path, song.lrcPath, song.durationMs,
            song.album, song.year,
        )
    }
}
