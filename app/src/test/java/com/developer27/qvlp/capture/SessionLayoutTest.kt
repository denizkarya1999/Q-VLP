package com.developer27.qvlp.capture

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime

class SessionLayoutTest {
    @Test fun namesACaptureAndItsProcessedFolderByPositionAndTime() {
        val name = SessionLayout.captureName(12, LocalDateTime.of(2026, 11, 14, 9, 5, 7))
        assertEquals("position_12_20261114_090507", name)
        val capture = File(File("session_x", "Original_Frames"), name)
        assertEquals(File("session_x"), SessionLayout.session(capture))
        assertEquals(File(File("session_x", "Processed_Frames"), "processed_position_frames_12_20261114_090507"),
            SessionLayout.processedFolder(capture))
        assertTrue(SessionLayout.isCapture(File(name)))
        assertFalse(SessionLayout.isCapture(File("processed_position_frames_12_20261114_090507")))
    }

    @Test fun capturesLiveInOriginalFramesAndTheirResultsInProcessedFrames() {
        val session = Files.createTempDirectory("session_").toFile()
        try {
            val capture = File(SessionLayout.originals(session), "position_3_20261114_090507").apply { mkdirs() }
            File(SessionLayout.originals(session), "notes.txt").writeText("not a capture")
            File(session, "position_9_stray").mkdirs() // Only Original_Frames holds captures.
            assertEquals(listOf(capture), SessionLayout.captures(session))
            assertEquals("Original_Frames/position_3_20261114_090507", capture.relativeTo(session).invariantSeparatorsPath)
            assertEquals("Processed_Frames/processed_position_frames_3_20261114_090507",
                SessionLayout.processedFolder(capture).relativeTo(session).invariantSeparatorsPath)
            assertTrue(SessionLayout.captures(File(session, "missing")).isEmpty())
        } finally { session.deleteRecursively() }
    }

    @Test fun aSessionSavedInTheEarlierLayoutIsMovedIntoTheTwoFolders() {
        val session = Files.createTempDirectory("session_").toFile()
        try {
            File(session, "final.csv").writeText("position_id\n")
            File(session, "session.json").writeText("{}")
            File(File(session, "position_1_20261114_185036/frames").apply { mkdirs() }, "frame_00000.png").writeBytes(byteArrayOf(1))
            File(File(session, "position_1_20261114_185036"), "burst.json").writeText("{}")
            File(File(session, "processed_position_frames_1_20261114_185036/processed_frames").apply { mkdirs() }, "frame_00000.png")
                .writeBytes(byteArrayOf(2))
            assertTrue(SessionLayout.adopt(session))
            assertEquals(setOf("final.csv", "session.json", "Original_Frames", "Processed_Frames"), session.list()!!.toSet())
            val capture = SessionLayout.captures(session).single()
            assertEquals("position_1_20261114_185036", capture.name)
            assertTrue(File(capture, "frames/frame_00000.png").isFile)
            assertTrue(File(capture, "burst.json").isFile)
            assertTrue(File(SessionLayout.processedFolder(capture), "processed_frames/frame_00000.png").isFile)
            // Nothing is left to move the second time.
            assertFalse(SessionLayout.adopt(session))
            assertEquals(1, SessionLayout.captures(session).size)
        } finally { session.deleteRecursively() }
    }

    @Test fun readsThePositionOutOfACaptureNameOnly() {
        assertEquals(12, SessionLayout.positionId("position_12_20261114_090507"))
        assertEquals(0, SessionLayout.positionId("position_0_20261114_090507-1"))
        assertNull(SessionLayout.positionId("processed_position_frames_12_20261114_090507"))
        assertNull(SessionLayout.positionId("final.csv"))
        assertNull(SessionLayout.positionId("position_x_1"))
    }

    @Test fun namesEachFramesPictureAndMetadata() {
        assertEquals("frame_00000.png", SessionLayout.frameName(0))
        assertEquals("frame_00029_metadata.json", SessionLayout.metadataName(29))
    }

    @Test fun onlyTheSessionsOwnFilesLeaveTheApp() {
        for (name in listOf("final.csv", "frame_00000.png", "frame_00000_metadata.json",
            "frame_and_metadata_mapping.csv", "processed_frame_and_metadata_mapping.csv")) {
            assertTrue(name, SessionLayout.isOutput(File(name)))
        }
        // Bookkeeping and temporary files stay in the app's private storage.
        for (name in listOf("session.json", "burst.json", "frame_00000.yuv.part",
            "frame_00000.png.part", "final.csv.new", "final.csv.bak", "burst.json.new")) {
            assertFalse(name, SessionLayout.isOutput(File(name)))
        }
    }

    @Test fun aMappingFilePairsEachPictureWithItsMetadata() {
        val text = SessionLayout.mapping(listOf(0, 1, 29))
        assertEquals("frame_file,metadata_file\nframe_00000.png,frame_00000_metadata.json\n" +
            "frame_00001.png,frame_00001_metadata.json\nframe_00029.png,frame_00029_metadata.json\n", text)
        val file = Files.createTempFile("mapping", ".csv").toFile()
        try {
            file.writeText(text)
            assertEquals(listOf("frame_00000.png" to "frame_00000_metadata.json", "frame_00001.png" to "frame_00001_metadata.json",
                "frame_00029.png" to "frame_00029_metadata.json"), SessionLayout.readMapping(file))
            file.writeText("something,else\na,b\n")
            assertTrue(SessionLayout.readMapping(file).isEmpty())
        } finally { file.delete() }
        assertTrue(SessionLayout.readMapping(File("does-not-exist.csv")).isEmpty())
    }
}
