package com.gravity.ide

import com.gravity.ide.core.MakeFiles
import com.gravity.ide.core.VideoPlan
import com.gravity.ide.core.WavJoin
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoPlanTest {
    @Test fun scenesLastAsLongAsTheirNarration() {
        assertEquals(listOf(2.5, 4.5), VideoPlan.durations(listOf(16000, 64000), 16000))
    }

    @Test fun pixelsBecomeVideoColours() {
        val y = ByteArray(4); val u = ByteArray(1); val v = ByteArray(1)
        VideoPlan.argbToYuv420(IntArray(4) { 0xFFFFFFFF.toInt() }, 2, 2, y, u, v)
        assertEquals(235, y[0].toInt() and 0xFF); assertEquals(128, u[0].toInt() and 0xFF); assertEquals(128, v[0].toInt() and 0xFF)
        VideoPlan.argbToYuv420(IntArray(4) { 0xFFFF0000.toInt() }, 2, 2, y, u, v)
        assertEquals(82, y[0].toInt() and 0xFF); assertEquals(90, u[0].toInt() and 0xFF); assertEquals(240, v[0].toInt() and 0xFF)
    }

    @Test fun wavSamplesAndResampling() {
        val (s, rate) = WavJoin.pcmMono16(WavJoin.pcm16(shortArrayOf(0, 100, 200, 300), 8000))
        assertEquals(8000, rate)
        assertEquals(listOf<Short>(0, 100, 200, 300), s.toList())
        assertEquals(8, WavJoin.resample(s, 8000, 16000).size)
    }

    @Test fun videoRepliesAreSplitIntoScenes() {
        val reply = "Scene 1: दिवाली की रात है।\n```svg\n<svg viewBox=\"0 0 720 1280\"><rect/></svg>\n```\n**Scene 2:** Diye jal rahe hain.\n```svg\n<svg><circle/></svg>\n```"
        val scenes = MakeFiles.scenes(reply)
        assertEquals(listOf("दिवाली की रात है।", "Diye jal rahe hain."), scenes.map { it.first })
        assertEquals("<svg><circle/></svg>", scenes[1].second)
        assertEquals(MakeFiles.Kind.VIDEO, MakeFiles.detect("मुझे दिवाली पर एक वीडियो बना दो"))
        assertEquals(null, MakeFiles.detect("video kaise banate hain?"))
    }

    @Test fun onlinePictureDescriptionsAreFound() {
        assertEquals("A playful brown monkey eating a banana on a tree, cartoon style",
            MakeFiles.picturePrompt("**Picture:** A playful brown monkey eating a banana on a tree, cartoon style\nYeh raha aapka bandar!"))
        val reply = "Scene 1: दिवाली की रात है।\nPicture: A night sky full of fireworks over an Indian village\n\n**Scene 2:** Diye jal rahe hain.\n**Picture:** Clay lamps glowing on a doorstep"
        val scenes = MakeFiles.pictureScenes(reply)
        assertEquals(listOf("दिवाली की रात है।", "Diye jal rahe hain."), scenes.map { it.first })
        assertEquals("Clay lamps glowing on a doorstep", scenes[1].second)
        org.junit.Assert.assertTrue(MakeFiles.instruction(MakeFiles.Kind.IMAGE, onlinePictures = true).contains("Picture:"))
        org.junit.Assert.assertTrue(MakeFiles.instruction(MakeFiles.Kind.IMAGE).contains("SVG"))
    }
}
