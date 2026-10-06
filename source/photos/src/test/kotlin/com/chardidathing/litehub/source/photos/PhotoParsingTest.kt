package com.chardidathing.litehub.source.photos

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoParsingTest {

    @Test
    fun `immich album images only`() {
        val ids = ImmichPhotos.parseAlbum(
            """{"id":"alb","albumName":"frame","assets":[{"id":"a1","type":"IMAGE"},{"id":"v1","type":"VIDEO"},{"id":"a2","type":"IMAGE"}]}""",
        )
        assertEquals(listOf("a1", "a2"), ids)
    }

    @Test
    fun `ha media browse splits images from folders`() {
        val result = Json.parseToJsonElement(
            """{"title":"frame","media_class":"directory","children":[
                {"title":"a.jpg","media_class":"image","media_content_id":"media-source://media_source/local/frame/a.jpg"},
                {"title":"2025","media_class":"directory","media_content_id":"media-source://media_source/local/frame/2025"},
                {"title":"clip.mp4","media_class":"video","media_content_id":"media-source://media_source/local/frame/clip.mp4"}]}""",
        )
        assertEquals(listOf("media-source://media_source/local/frame/a.jpg"), HaMediaPhotos.images(result))
        assertEquals(listOf("media-source://media_source/local/frame/2025"), HaMediaPhotos.folders(result))
    }
}
