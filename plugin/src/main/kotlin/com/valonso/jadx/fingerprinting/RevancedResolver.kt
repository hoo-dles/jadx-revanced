package com.valonso.jadx.fingerprinting

import app.revanced.patcher.Fingerprint
import app.revanced.patcher.Patcher
import app.revanced.patcher.PatcherConfig
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.iface.Method
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID

object RevancedResolver {
    private lateinit var sourceApk: File
    private lateinit var patcherTemporaryFilesPath: File

    fun createPatcher(
        sourceApk: File,
        patcherTemporaryFilesPath: File,
    ) {
        this.sourceApk = sourceApk

        // Add a random suffix to the temporary files path
        this.patcherTemporaryFilesPath = File(
            patcherTemporaryFilesPath,
            UUID.randomUUID().toString()
        )
        ScriptEvaluation.LOG.info { "Called createPatcher with $sourceApk and ${this.patcherTemporaryFilesPath}" }
        GlobalScope.launch(Dispatchers.IO) {
            ScriptEvaluation
        }
    }

    fun searchFingerprint(fingerprint: Fingerprint): Method? {
        if (!::sourceApk.isInitialized || !::patcherTemporaryFilesPath.isInitialized) {
            ScriptEvaluation.LOG.error { "Patcher not initialized" }
            return null
        }
        val patcher = Patcher(
            PatcherConfig(
                this.sourceApk,
                this.patcherTemporaryFilesPath,
                null,
                this.patcherTemporaryFilesPath.absolutePath,
            ),
        )
        var searchResult: Method? = null

        val tempPatch = bytecodePatch(
            name = "Temporary patch for searching fingerprint"
        ) {
            execute {
                ScriptEvaluation.LOG.info { "Inside execute" }
                searchResult = fingerprint.originalMethodOrNull
                ScriptEvaluation.LOG.info { "Fingerprint found: $searchResult" }
            }

        }

        patcher.use {
            it += setOf(tempPatch)
            runBlocking {
                it().collect { result ->
                    val exception = result.exception
                        ?: return@collect ScriptEvaluation.LOG.info { "\"${result.patch}\" succeeded" }
                    ScriptEvaluation.LOG.error(exception) { "\"${result.patch}\" failed:\n" }
                }
            }
        }
        ScriptEvaluation.LOG.info { "Outside of block $searchResult" }

        return searchResult
    }
}
