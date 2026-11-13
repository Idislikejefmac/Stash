package app.stash

import app.stash.domain.CameraFlash
import app.stash.domain.CameraGate
import app.stash.domain.CameraNext
import app.stash.domain.CameraRules
import app.stash.domain.CameraShot
import app.stash.domain.Capability
import app.stash.domain.JpegOrientation
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Card 16e: the pure parts, with no Android and no camera. */
class CameraRulesTest {
    private val shot = CameraShot(byteArrayOf(1, 2, 3), 90)

    // ---------------------------------------------------------------- what happens after the shutter

    @Test fun aShotForANewNoteGoesToTheAnnotateScreen() {
        assertEquals(CameraNext.Step.ANNOTATE, CameraNext.after(shot, null))
    }

    @Test fun aShotForAnExistingNoteIsAttachedAtOnce() {
        assertEquals(CameraNext.Step.ATTACH, CameraNext.after(shot, "capture-id"))
    }

    // ---------------------------------------------------------------- the camera switch and its row

    @Test fun theCameraCapabilityIsOnByDefaultNeedsOnlyTheCameraPermissionAndSaysWhatStillWorks() {
        val c = Capability.CAMERA
        assertEquals(listOf("android.permission.CAMERA"), c.permissions)
        assertTrue(c.defaultOn)
        assertEquals("You can still share pictures in and pick them from your gallery.", c.withoutIt)
    }

    @Test fun cameraButtonsAreDrawnOnlyWhenTheSwitchAndPicturesAreOnAndThePhoneHasACamera() {
        for (sw in listOf(false, true)) for (pics in listOf(false, true)) for (cam in listOf(false, true)) {
            assertEquals("switch=$sw pictures=$pics camera=$cam", sw && pics && cam, CameraRules.buttonVisible(sw, pics, cam))
        }
    }

    // ---------------------------------------------------------------- permission and lock state

    private fun gate(sw: Boolean = true, pics: Boolean = true, cam: Boolean = true, granted: Boolean = true, locked: Boolean = false) =
        CameraRules.gate(sw, pics, cam, granted, locked)

    @Test fun withThePermissionTheCameraOpensLockedOrNot() {
        assertEquals(CameraGate.OPEN, gate())
        assertEquals(CameraGate.OPEN, gate(locked = true))
    }

    @Test fun withoutThePermissionAnUnlockedPhoneAsks() {
        assertEquals(CameraGate.ASK_PERMISSION, gate(granted = false))
    }

    @Test fun withoutThePermissionALockedPhoneSaysToOpenStashOnce() {
        assertEquals(CameraGate.ALLOW_IN_STASH, gate(granted = false, locked = true))
        assertEquals("Open Stash once and allow the camera.", CameraRules.message(CameraGate.ALLOW_IN_STASH))
    }

    @Test fun aSwitchedOffCameraOrPicturesOrNoCameraBeatsEverythingElse() {
        assertEquals(CameraGate.SWITCHED_OFF, gate(sw = false, pics = false, cam = false, granted = false))
        assertEquals(CameraGate.PICTURES_OFF, gate(pics = false, cam = false))
        assertEquals(CameraGate.NO_CAMERA, gate(cam = false, granted = false))
    }

    @Test fun everyGateThatStopsTheCameraSaysSoInPlainWords() {
        for (g in CameraGate.entries) {
            val m = CameraRules.message(g)
            if (g == CameraGate.OPEN || g == CameraGate.ASK_PERMISSION) assertNull(m) else assertTrue(g.name, !m.isNullOrBlank())
        }
        assertTrue(CameraRules.message(CameraGate.SWITCHED_OFF)!!.contains("You can still share pictures in and pick them from your gallery."))
        assertTrue(CameraRules.NEEDS_PERMISSION.contains("gallery"))
    }

    // ---------------------------------------------------------------- the lock-screen fallback (item 9)

    @Test fun lockedTheButtonSaysUnlockToTakeAPictureAndUnlocksFirst() {
        // The documented fallback is on until the phone check says the camera opens over the lock screen.
        assertTrue(CameraRules.UNLOCK_FIRST_WHEN_LOCKED)
        assertEquals("Unlock to take a picture", CameraRules.takeLabel(locked = true))
        assertTrue(CameraRules.needsUnlockFirst(locked = true))
    }

    @Test fun unlockedTheButtonJustSaysTakeAPictureAndNeverUnlocks() {
        assertEquals("Take a picture", CameraRules.takeLabel(locked = false))
        assertFalse(CameraRules.needsUnlockFirst(locked = false))
    }

    // ---------------------------------------------------------------- flash and wording

    @Test fun flashHasExactlyOffAndAutoAndTogglesBetweenThem() {
        assertEquals(listOf(CameraFlash.OFF, CameraFlash.AUTO, CameraFlash.ON), CameraFlash.entries)
        assertEquals(CameraFlash.AUTO, CameraRules.nextFlash(CameraFlash.OFF))
        assertEquals(CameraFlash.ON, CameraRules.nextFlash(CameraFlash.AUTO))
        assertEquals(CameraFlash.OFF, CameraRules.nextFlash(CameraFlash.ON))
        assertEquals("Flash: off", CameraRules.flashLabel(CameraFlash.OFF))
        assertEquals("Flash: auto", CameraRules.flashLabel(CameraFlash.AUTO))
    }

    @Test fun theAddedLineCountsPictures() {
        assertEquals("Added 1 picture", CameraRules.added(1))
        assertEquals("Added 3 pictures", CameraRules.added(3))
    }

    // ---------------------------------------------------------------- rotation, applied once

    /** A tiny but well-formed JPEG: SOI, JFIF, [extra segments], SOS with scan bytes (including FF 00 stuffing), EOI. */
    private fun jpeg(vararg segments: ByteArray): ByteArray {
        val jfif = segment(0xE0, byteArrayOf('J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0, 1, 1, 0, 0, 1, 0, 1, 0, 0))
        val scan = segment(0xDA, byteArrayOf(1, 1, 0, 0, 63, 0)) + byteArrayOf(0x12, 0xFF.toByte(), 0, 0x34, 0x56) + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        var out = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + jfif
        for (s in segments) out += s
        return out + scan
    }

    private fun segment(marker: Int, body: ByteArray): ByteArray {
        val len = body.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (len shr 8).toByte(), len.toByte()) + body
    }

    /** An Exif segment with one orientation entry, either byte order, plus a GPS-ish pointer entry that must not survive. */
    private fun exif(orientation: Int, bigEndian: Boolean): ByteArray {
        fun u16(v: Int) = if (bigEndian) byteArrayOf((v shr 8).toByte(), v.toByte()) else byteArrayOf(v.toByte(), (v shr 8).toByte())
        fun u32(v: Int) = if (bigEndian) byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
        else byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        val order = if (bigEndian) "MM" else "II"
        val tiff = order.toByteArray() + u16(0x2A) + u32(8) + u16(2) +
            u16(0x0112) + u16(3) + u32(1) + u16(orientation) + u16(0) +
            u16(0x8825) + u16(4) + u32(1) + u32(0x1234) + // a GPS pointer
            u32(0)
        return segment(0xE1, "Exif".toByteArray() + byteArrayOf(0, 0) + tiff)
    }

    private fun app1Count(b: ByteArray): Int {
        var n = 0
        var i = 2
        while (i + 3 < b.size && b[i] == 0xFF.toByte()) {
            val m = b[i + 1].toInt() and 0xFF
            if (m == 0xDA) break
            if (m == 0xE1) n++
            i += 2 + (((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF))
        }
        return n
    }

    @Test fun theTagForEachRotationIsTheStandardOne() {
        assertEquals(1, JpegOrientation.tagFor(0))
        assertEquals(6, JpegOrientation.tagFor(90))
        assertEquals(3, JpegOrientation.tagFor(180))
        assertEquals(8, JpegOrientation.tagFor(270))
        assertEquals(8, JpegOrientation.tagFor(-90))
        assertEquals(6, JpegOrientation.tagFor(450))
    }

    @Test fun aJpegWithNoTagGetsExactlyOneForTheCamerasRotation() {
        for ((deg, tag) in listOf(0 to 1, 90 to 6, 180 to 3, 270 to 8)) {
            val out = JpegOrientation.withOrientation(jpeg(), deg)
            assertEquals(tag, JpegOrientation.read(out))
            assertEquals(1, app1Count(out))
        }
    }

    @Test fun aJpegThatAlreadyCarriesATagHasItReplacedNotAddedTo() {
        for (big in listOf(true, false)) {
            val original = jpeg(exif(orientation = 6, bigEndian = big))
            assertEquals(6, JpegOrientation.read(original))
            // The camera says the picture is already upright: the old tag must not turn it again.
            val upright = JpegOrientation.withOrientation(original, 0)
            assertEquals(1, JpegOrientation.read(upright))
            assertEquals(1, app1Count(upright))
            // The camera says turn it 270: that, and only that.
            val turned = JpegOrientation.withOrientation(original, 270)
            assertEquals(8, JpegOrientation.read(turned))
            assertEquals(1, app1Count(turned))
        }
    }

    @Test fun otherPositionDataInTheOldExifIsDroppedAndEverythingElseIsKeptByteForByte() {
        val dqt = segment(0xDB, ByteArray(65) { it.toByte() })
        val original = jpeg(exif(6, true), dqt)
        val out = JpegOrientation.withOrientation(original, 90)
        // The old Exif (with its GPS pointer) is gone: its 0x8825 entry bytes do not appear.
        val s = out.toList()
        assertFalse(s.windowed(2).any { it == listOf(0x88.toByte(), 0x25.toByte()) })
        // The quantisation table and the scan, with its stuffed FF 00, come through untouched.
        val tail = original.copyOfRange(original.size - 17, original.size) // SOS segment (10) + scan bytes (5) + EOI (2)
        assertArrayEquals(tail, out.copyOfRange(out.size - tail.size, out.size))
        val str = String(out, Charsets.ISO_8859_1)
        assertTrue(str.contains(String(dqt, Charsets.ISO_8859_1)))
    }

    @Test fun somethingThatIsNotAJpegIsReturnedUnchanged() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        assertSame(png, JpegOrientation.withOrientation(png, 90))
        val empty = ByteArray(0)
        assertSame(empty, JpegOrientation.withOrientation(empty, 90))
        assertNull(JpegOrientation.read(png))
    }

    @Test fun aDamagedJpegNeverThrows() {
        val whole = jpeg(exif(6, true))
        for (cut in 0..whole.size step 3) {
            val part = whole.copyOf(cut)
            JpegOrientation.withOrientation(part, 90)
            JpegOrientation.read(part)
        }
        // A segment that claims to be longer than the file.
        val lying = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0x7F, 0xFF.toByte(), 1, 2, 3)
        JpegOrientation.withOrientation(lying, 90)
        assertNull(JpegOrientation.read(lying))
    }
}
