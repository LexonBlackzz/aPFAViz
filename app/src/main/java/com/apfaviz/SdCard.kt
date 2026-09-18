package com.apfaviz

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/**
 * "Pagefile Location = SD Card" — the storage side of the streaming pool.
 *
 * A streaming load writes the 72-byte event pool to a temp file and maps it
 * (streamer.h). For the MIDIs that need streaming at all that file is huge:
 * ~5.5 GB for RDR 40M, ~10.5 GB for U11. Phones that ship with a card slot are
 * exactly the phones whose internal storage cannot hold that, so the pool can
 * optionally live in the app's cache dir on the card instead.
 *
 * The option is offered only where it can actually work. Three hard gates,
 * checked in this order so the user sees the first thing that is wrong:
 *
 *  1. **A 64-bit process**, which needs both a 64-bit CPU and a 64-bit
 *     Android build. These are reported separately only so the message can be
 *     honest about which one is missing: a 32-bit ROM makes a 64-bit SoC look
 *     like an ARMv7 part in /proc/cpuinfo, so a phone is never told its
 *     processor is 32-bit unless nothing contradicts it. The requirement: the
 *     pool is mapped into the process's address space in one contiguous
 *     reservation, and a 32-bit process has under 3 GB of user address space
 *     for everything. A pagefile big enough to be worth moving to a card
 *     cannot be mapped there at all — the reservation fails before a byte is
 *     written. This is what the 1.2.0-era SD support got wrong: it offered the
 *     option on 32-bit ROMs (LG X410, K30), where those devices then could not
 *     play at all.
 *
 * Plus one soft gate: a card has to actually be mounted and writable.
 */
object SdCard {

    /**
     * Why the SD card cannot be used on this device, or null when it can. The
     * string is shown under the (disabled) "SD Card" choice, so it says what is
     * wrong rather than just that something is.
     */
    fun unavailableReason(ctx: Context): String? {
        // A 32-bit process is the hard blocker; whether the CPU or the OS is
        // responsible only changes what we can honestly tell the user. Never
        // claim the processor is 32-bit unless nothing suggests otherwise — a
        // 32-bit ROM makes a 64-bit SoC look 32-bit (see isCpu64Bit).
        if (!isProcess64Bit())
            return if (isCpu64Bit())
                "Needs a 64-bit Android build — this phone's CPU is 64-bit, " +
                "but it shipped with a 32-bit OS."
            else
                "Needs a 64-bit CPU and a 64-bit Android build — this device " +
                "is running 32-bit. Some phones have a 64-bit CPU but shipped " +
                "with a 32-bit OS, which can't do this either."
        if (cacheDir(ctx) == null)
            return "No SD card detected."
        return null
    }

    /**
     * The app's own cache dir on the card (created if needed), or null when
     * there is no usable secondary volume. No permission is involved: this
     * path is app-private on every API level from 19 up.
     */
    fun cacheDir(ctx: Context): File? {
        // Slot 0 is the built-in "external" storage — emulated, and on the same
        // physical flash as internal, so moving the pagefile there buys
        // nothing. Slots after it are separate volumes, i.e. the card. A null
        // slot is a volume that is not mounted right now.
        val dirs = try { ctx.externalCacheDirs } catch (e: Exception) { null } ?: return null
        for (i in 1 until dirs.size) {
            val d = dirs[i] ?: continue
            if (!d.exists() && !d.mkdirs()) continue
            if (d.canWrite()) return d
        }
        return null
    }

    /**
     * Delete pagefile temps left on the card by a load that died. Unlike the
     * internal path, which unlinks its temps the moment it creates them, the
     * card path keeps them named (see streamer.h) — so a process death can
     * strand several GB there with nothing else to reclaim it. The streamer
     * sweeps before each load too; this one runs at launch so the space comes
     * back even if the user never opens another MIDI.
     */
    fun sweep(ctx: Context) {
        val dir = cacheDir(ctx) ?: return
        val stale = dir.listFiles { f -> f.isFile && f.name.startsWith("apfa_") } ?: return
        var freed = 0L
        for (f in stale) {
            val len = f.length()
            if (f.delete()) freed += len
        }
        if (freed > 0)
            Log.i("aPFAViz", "swept %.1f MB of stale SD pagefile temps".format(freed / 1048576.0))
    }

    // Is the SoC 64-bit? Asked separately from the ROM so a 64-bit phone
    // running a 32-bit build gets told that, rather than being told its CPU is
    // something it isn't.
    private fun isCpu64Bit(): Boolean {
        // A ROM that lists 64-bit ABIs settles it (API 21+).
        if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty())
            return true
        // 32-bit ROM: ask the kernel. This is only ever allowed to promote a
        // "no" to a "yes" — every test below is one-way evidence OF 64-bit, and
        // failing them all means "we couldn't tell", not "the CPU is 32-bit".
        // The option stays disabled either way; what changes is whether we
        // blame the processor in the message.
        return try {
            File("/proc/cpuinfo").readLines().any { line ->
                val l = line.lowercase()
                when {
                    l.startsWith("processor") -> l.contains("aarch64")
                    l.startsWith("cpu architecture") ->
                        (l.substringAfter(':').trim().toIntOrNull() ?: 0) >= 8
                    // A 32-bit kernel on an ARMv8 SoC lies here: the LG X410
                    // (Snapdragon 425, Cortex-A53 — a 64-bit core) reports
                    // "model name: ARMv7 Processor" and "CPU architecture: 7".
                    // The feature list gives it away. AES/PMULL/SHA1/SHA2 and
                    // CRC32 are ARMv8-A instructions that no ARMv7 part has, so
                    // seeing any of them proves an ARMv8 core underneath.
                    // (Their absence proves nothing — they are optional on
                    // ARMv8 — which is exactly why this test only ever adds.)
                    l.startsWith("features") -> {
                        val f = l.substringAfter(':').split(' ')
                        f.any { it == "aes" || it == "pmull" || it == "sha1" ||
                                it == "sha2" || it == "crc32" }
                    }
                    else -> false
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    // API 23+ exposes the process ABI directly.
    private fun isProcess64Bit(): Boolean = android.os.Process.is64Bit()
}