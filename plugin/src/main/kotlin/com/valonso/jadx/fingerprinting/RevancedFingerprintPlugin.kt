package com.valonso.jadx.fingerprinting

import com.android.tools.smali.dexlib2.analysis.reflection.util.ReflectionUtils
import com.valonso.jadx.fingerprinting.solver.Solver
import jadx.api.plugins.JadxPlugin
import jadx.api.plugins.JadxPluginContext
import jadx.api.plugins.JadxPluginInfo
import io.github.oshai.kotlinlogging.KotlinLogging
import jadx.api.plugins.JadxPluginInfoBuilder
import lanchon.multidexlib2.BasicDexFileNamer
import lanchon.multidexlib2.MultiDexIO

class RevancedFingerprintPlugin : JadxPlugin {
    companion object {
        const val ID = "jadx-revanced"
        private val LOG = KotlinLogging.logger("$ID/plugin")
    }

    override fun getPluginInfo(): JadxPluginInfo {
        return JadxPluginInfoBuilder.pluginId(ID)
            .name("JADX Revanced")
            .description("Revanced fingerprint scripting for JADX")
            .requiredJadxVersion("1.5.2, r2491")
            .build()
    }

    override fun init(init: JadxPluginContext) {
        LOG.info { init.args }
        LOG.info { init.args.inputFiles }

        val sourceApk = init.args.inputFiles.firstOrNull()
        if (sourceApk == null || !sourceApk.exists()) {
            LOG.error { "No APK file found" }
            return
        }
        RevancedResolver.createPatcher(sourceApk, init.files().pluginTempDir.toFile())

        MultiDexIO.readDexFile(
            true,
            sourceApk,
            BasicDexFileNamer(),
            null,
            null,
        ).classes.flatMap { classDef ->
            classDef.methods
        }.let { allMethods ->
            Solver.setMethods(allMethods)
        }

        LOG.info { "Revanced fingerprint plugin is enabled" }
        init.guiContext?.let {
            RevancedFingerprintPluginUi.init(init)
        }
    }
}

fun main() {
    println(ReflectionUtils.dexToJavaName(
        "Lcom/datatheorem/android/trustkit/config/DomainPinningPolicy;"
    ))
}