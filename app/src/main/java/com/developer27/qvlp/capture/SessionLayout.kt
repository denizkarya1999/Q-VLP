package com.developer27.qvlp.capture

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The folders and files a session contains:
 *
 *     session_<id>/
 *       final.csv
 *       position_<id>_<timestamp>/                    one per capture
 *         frames/frame_00000.png …
 *         metadata/frame_00000_metadata.json …        camera settings of each frame
 *         frame_and_metadata_mapping.csv
 *       processed_position_frames_<id>_<timestamp>/   the same capture after LED detection
 *         processed_frames/frame_00000.png …
 *         processed_frame_metadata/frame_00000_metadata.json …
 *         processed_frame_and_metadata_mapping.csv
 */
internal object SessionLayout {
    const val FINAL = "final.csv"
    const val FRAMES = "frames"
    const val METADATA = "metadata"
    const val MAPPING = "frame_and_metadata_mapping.csv"
    const val PROCESSED_FRAMES = "processed_frames"
    const val PROCESSED_METADATA = "processed_frame_metadata"
    const val PROCESSED_MAPPING = "processed_frame_and_metadata_mapping.csv"
    const val MAPPING_HEADER = "frame_file,metadata_file"
    private const val CAPTURE_PREFIX = "position_"
    private const val PROCESSED_PREFIX = "processed_position_frames_"
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    fun captureName(positionId: Int, time: LocalDateTime): String = "$CAPTURE_PREFIX${positionId}_${STAMP.format(time)}"
    fun isCapture(folder: File): Boolean = folder.name.startsWith(CAPTURE_PREFIX)
    fun processedFolder(capture: File): File =
        File(capture.parentFile, PROCESSED_PREFIX + capture.name.removePrefix(CAPTURE_PREFIX))

    /** The position a capture folder belongs to, or null for a name that is not a capture. */
    fun positionId(captureName: String): Int? =
        captureName.removePrefix(CAPTURE_PREFIX).takeIf { it != captureName }?.substringBefore('_')?.toIntOrNull()

    fun frameName(frameId: Int): String = "frame_${frameId.toString().padStart(5, '0')}.png"
    fun metadataName(frameId: Int): String = "frame_${frameId.toString().padStart(5, '0')}_metadata.json"

    /**
     * Whether a file belongs to the session as listed above. The app keeps a little bookkeeping of its own beside
     * them (session.json and each capture's burst.json: state and warnings) and writes through temporary files.
     * None of that ever leaves the app's private storage.
     */
    fun isOutput(file: File): Boolean = file.name !in BOOKKEEPING && TEMPORARY.none { file.name.endsWith(it) }

    private val BOOKKEEPING = setOf("session.json", "burst.json")
    private val TEMPORARY = listOf(".part", ".bak", ".new")

    /** One mapping file: each frame picture beside the metadata file that describes it. */
    fun mapping(frameIds: List<Int>): String =
        (listOf(MAPPING_HEADER) + frameIds.map { "${frameName(it)},${metadataName(it)}" }).joinToString("\n", postfix = "\n")

    /** The (frame picture, metadata file) pairs of a mapping file; empty when it is missing or unreadable. */
    fun readMapping(file: File): List<Pair<String, String>> {
        val lines = runCatching { file.readLines() }.getOrNull() ?: return emptyList()
        if (lines.firstOrNull() != MAPPING_HEADER) return emptyList()
        return lines.drop(1).filter { it.isNotBlank() }.mapNotNull { line ->
            line.split(',').takeIf { it.size == 2 }?.let { it[0] to it[1] }
        }
    }
}
