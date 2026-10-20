package com.example.audio

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.example.model.Song

/**
 * Reads audio indexed by Android MediaStore.
 *
 * Call only after the app has READ_MEDIA_AUDIO (Android 13+) or
 * READ_EXTERNAL_STORAGE (Android 12L and earlier) permission.
 */
class LocalAudioLibrary(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    fun scanDeviceAudio(): List<Song> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

        val songs = mutableListOf<Song>()

        resolver.query(
            collection,
            projection,
            selection,
            null,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

            while (cursor.moveToNext()) {
                val mediaId = cursor.getLong(idColumn)
                val title = cursor.getString(titleColumn)
                    ?.takeIf { it.isNotBlank() }
                    ?: "Unknown title"
                val artist = cursor.getString(artistColumn)
                    ?.takeIf { it.isNotBlank() && it != MediaStore.UNKNOWN_STRING }
                    ?: "Unknown artist"
                val durationMs = cursor.getLong(durationColumn).coerceAtLeast(0L)

                // MediaStore row ID is stable for this indexed item; do not use a random timestamp.
                val contentUri = ContentUris.withAppendedId(collection, mediaId)

                songs += Song(
                    id = "media:$mediaId",
                    title = title,
                    artist = artist,
                    durationMs = durationMs,
                    fileUri = contentUri
                )
            }
        }

        return songs
    }
}
