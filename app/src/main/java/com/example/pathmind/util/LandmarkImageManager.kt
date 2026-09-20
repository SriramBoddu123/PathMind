package com.example.pathmind.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.max

/**
 * Stage 9: Landmark Image Storage & Processing Utility.
 * Manages private local photo storage, FileProvider URIs, downscaling, EXIF orientation,
 * and JPEG compression without external computer vision or ML libraries.
 */
object LandmarkImageManager {

    private const val TAG = "LandmarkImageManager"
    private const val LANDMARKS_DIR_NAME = "landmarks"
    private const val TEMP_DIR_NAME = "landmark_temp"
    private const val TARGET_MAX_DIMENSION = 1024
    private const val JPEG_COMPRESSION_QUALITY = 80

    /**
     * Returns the dedicated private landmarks directory in internal storage.
     */
    fun getLandmarksDir(context: Context): File {
        val dir = File(context.filesDir, LANDMARKS_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Creates a temporary capture file and returns its FileProvider content URI.
     */
    fun createTempCaptureUri(context: Context): Pair<Uri, File> {
        val tempDir = File(context.cacheDir, TEMP_DIR_NAME)
        if (!tempDir.exists()) {
            tempDir.mkdirs()
        }
        val tempFile = File(tempDir, "temp_capture_${System.currentTimeMillis()}.jpg")
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, tempFile)
        return Pair(uri, tempFile)
    }

    /**
     * Processes a captured raw image: inspects bounds, downscales to <= 1024px,
     * corrects EXIF orientation, compresses to JPEG (quality 80), and stores in private internal storage.
     * Returns the absolute path of the saved JPEG file, or null on failure.
     */
    fun processAndSaveLandmark(context: Context, sourceFile: File, memoryId: String): String? {
        if (!sourceFile.exists() || sourceFile.length() == 0L) {
            Log.w(TAG, "Source image file missing or empty: ${sourceFile.absolutePath}")
            return null
        }

        return try {
            val startTime = System.currentTimeMillis()

            // 1. Read EXIF Orientation
            val rotationDegrees = getExifRotationDegrees(sourceFile)

            // 2. Decode scaled bitmap
            val decodedBitmap = decodeSampledBitmap(sourceFile, TARGET_MAX_DIMENSION)
            if (decodedBitmap == null) {
                Log.e(TAG, "Failed to decode bitmap from ${sourceFile.absolutePath}")
                return null
            }

            // 3. Apply rotation if needed
            val finalBitmap = if (rotationDegrees != 0) {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                val rotated = Bitmap.createBitmap(
                    decodedBitmap, 0, 0,
                    decodedBitmap.width, decodedBitmap.height,
                    matrix, true
                )
                if (rotated != decodedBitmap) {
                    decodedBitmap.recycle()
                }
                rotated
            } else {
                decodedBitmap
            }

            // 4. Compress and save to private filesDir/landmarks/
            val landmarksDir = getLandmarksDir(context)
            val destinationFile = File(landmarksDir, "landmark_${memoryId}.jpg")

            FileOutputStream(destinationFile).use { fos ->
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_COMPRESSION_QUALITY, fos)
                fos.flush()
            }
            finalBitmap.recycle()

            // 5. Clean up temporary source file
            if (sourceFile.exists() && sourceFile.absolutePath.contains(context.cacheDir.absolutePath)) {
                sourceFile.delete()
            }

            val durationMs = System.currentTimeMillis() - startTime
            val fileSizeKb = destinationFile.length() / 1024
            Log.d(TAG, "Saved landmark photo for $memoryId: ${fileSizeKb}KB in ${durationMs}ms at ${destinationFile.absolutePath}")

            destinationFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Error processing landmark photo for $memoryId", e)
            null
        }
    }

    /**
     * Safely loads a downscaled bitmap for UI display.
     */
    fun loadLandmarkBitmap(filePath: String?, maxDim: Int = 1024): Bitmap? {
        if (filePath.isNullOrBlank()) return null
        val file = File(filePath)
        if (!file.exists()) return null

        return try {
            decodeSampledBitmap(file, maxDim)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load landmark bitmap from $filePath", e)
            null
        }
    }

    /**
     * Deletes the landmark photo file from disk if it exists.
     */
    fun deleteLandmarkImage(filePath: String?): Boolean {
        if (filePath.isNullOrBlank()) return false
        return try {
            val file = File(filePath)
            if (file.exists()) {
                val deleted = file.delete()
                Log.d(TAG, "Deleted landmark file: $filePath (success: $deleted)")
                deleted
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete landmark file: $filePath", e)
            false
        }
    }

    private fun decodeSampledBitmap(file: File, maxDimension: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(file.absolutePath, options)

        val rawWidth = options.outWidth
        val rawHeight = options.outHeight
        if (rawWidth <= 0 || rawHeight <= 0) return null

        var sampleSize = 1
        val maxRawDim = max(rawWidth, rawHeight)
        while ((maxRawDim / sampleSize) > maxDimension) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565 // Low memory footprint
        }

        return BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
    }

    private fun getExifRotationDegrees(file: File): Int {
        return try {
            val exif = ExifInterface(file.absolutePath)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read EXIF orientation: ${e.message}")
            0
        }
    }
}
