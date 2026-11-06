package com.example.anyshare

import com.example.anyshare.data.local.HistoryEntity
import com.example.anyshare.history.HistoryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryCategoryTest {

    @Test
    fun testResolveCategoryMedia() {
        assertEquals(HistoryEntity.CATEGORY_MEDIA, HistoryEntity.resolveCategory("vacation.jpg", "image/jpeg"))
        assertEquals(HistoryEntity.CATEGORY_MEDIA, HistoryEntity.resolveCategory("video.mp4", "video/mp4"))
        assertEquals(HistoryEntity.CATEGORY_MEDIA, HistoryEntity.resolveCategory("song.mp3", "audio/mpeg"))
        assertEquals(HistoryEntity.CATEGORY_MEDIA, HistoryEntity.resolveCategory("clip.mkv", "*/*"))
    }

    @Test
    fun testResolveCategoryDocuments() {
        assertEquals(HistoryEntity.CATEGORY_DOCUMENT, HistoryEntity.resolveCategory("assignment.pdf", "application/pdf"))
        assertEquals(HistoryEntity.CATEGORY_DOCUMENT, HistoryEntity.resolveCategory("thesis.docx", "*/*"))
        assertEquals(HistoryEntity.CATEGORY_DOCUMENT, HistoryEntity.resolveCategory("archive.zip", "application/zip"))
    }

    @Test
    fun testResolveCategoryApps() {
        assertEquals(HistoryEntity.CATEGORY_APP, HistoryEntity.resolveCategory("game.apk", "application/vnd.android.package-archive"))
        assertEquals(HistoryEntity.CATEGORY_APP, HistoryEntity.resolveCategory("anyshare.apk", "*/*"))
    }

    @Test
    fun testResolveCategoryNotes() {
        assertEquals(HistoryEntity.CATEGORY_NOTE, HistoryEntity.resolveCategory("note_12345.txt", "text/plain"))
    }

    @Test
    fun testHistoryItemEntityConversion() {
        val item = HistoryItem(
            fileName = "photo.png",
            fileSize = 2048L,
            mimeType = "image/png",
            isSender = true,
            fileUriString = "content://media/1"
        )
        assertEquals(HistoryEntity.CATEGORY_MEDIA, item.category)

        val entity = item.toEntity()
        assertEquals(HistoryEntity.CATEGORY_MEDIA, entity.category)
        assertEquals("photo.png", entity.fileName)
        assertTrue(entity.isSender)

        val convertedBack = HistoryItem.fromEntity(entity.copy(id = 42L))
        assertEquals(42L, convertedBack.dbId)
        assertEquals("42", convertedBack.id)
        assertEquals(HistoryEntity.CATEGORY_MEDIA, convertedBack.category)
    }
}
