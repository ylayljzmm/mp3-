package com.suisheng.player

import android.os.Parcel
import android.os.Parcelable
import com.suisheng.player.model.Song

data class SongParcelable(
    val id: Long,
    val title: String,
    val artist: String,
    val path: String,
    val lrcPath: String?,
    val durationMs: Long = 0L
) : Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readLong(),
        parcel.readString() ?: "",
        parcel.readString() ?: "",
        parcel.readString() ?: "",
        parcel.readString(),
        parcel.readLong()
    )

    fun toSong() = Song(id, title, artist, path, lrcPath, durationMs)

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeLong(id)
        parcel.writeString(title)
        parcel.writeString(artist)
        parcel.writeString(path)
        parcel.writeString(lrcPath)
        parcel.writeLong(durationMs)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<SongParcelable> {
        override fun createFromParcel(parcel: Parcel): SongParcelable = SongParcelable(parcel)
        override fun newArray(size: Int): Array<SongParcelable?> = arrayOfNulls(size)

        fun from(song: Song) = SongParcelable(
            song.id, song.title, song.artist, song.path, song.lrcPath, song.durationMs
        )
    }
}
