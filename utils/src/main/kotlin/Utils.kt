package com.valonso.utils

import app.revanced.patcher.Fingerprint
import app.revanced.patcher.Patcher
import app.revanced.patcher.PatcherConfig
import app.revanced.patcher.patch.BytecodePatch
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.util.proxy.mutableTypes.MutableMethod
import java.io.File


class RevancedResolver(
    private val sourceApk: File,
    private val patcherTemporaryFilesPath: File,
) {

    private val patcher: Patcher = Patcher(
        PatcherConfig(
            sourceApk,
            patcherTemporaryFilesPath,
            null,
            patcherTemporaryFilesPath.absolutePath,
        ),
    )

    fun searchFingerprint(fingerprint: Fingerprint): MutableMethod? {
        var searchResult: MutableMethod? = null
        patcher.use { patcher ->
            val tempPatch = bytecodePatch(
                name = "Temporary patch for searching fingerprint"
            ) {
                execute {
                    searchResult = fingerprint.methodOrNull
                }

            }
            patcher += setOf(tempPatch)
            runBlocking {
                patcher()
            }

        }
        return searchResult
    }
}