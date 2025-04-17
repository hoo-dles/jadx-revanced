package com.valonso.jadx.fingerprinting

import com.android.tools.smali.dexlib2.analysis.reflection.util.ReflectionUtils
import com.valonso.jadx.fingerprinting.RevancedFingerprintPluginUi.inlineSvgIcon
import com.valonso.jadx.fingerprinting.RevancedFingerprintPluginUi.showScriptPanel
import jadx.api.plugins.JadxPlugin
import jadx.api.plugins.JadxPluginContext
import jadx.api.plugins.JadxPluginInfo
import java.io.File


import io.github.oshai.kotlinlogging.KotlinLogging
import jadx.gui.utils.UiUtils

class RevancedFingerprintPlugin : JadxPlugin {
    companion object {
        const val ID = "jadx-revanced"
        private val LOG = KotlinLogging.logger("$ID/plugin")
    }

    private lateinit var context: JadxPluginContext

    private val pluginOptions = PluginOptions()
    override fun getPluginInfo() = JadxPluginInfo(ID, "JADX Revanced", "Revanced fingerprint scripting for JADX")
    lateinit var revancedResolver: RevancedResolver

    override fun init(init: JadxPluginContext) {
        this.context = init
        this.context.registerOptions(pluginOptions)
        if (!pluginOptions.enabled) {
            LOG.info { "Revanced fingerprint plugin is disabled" }
            return
        }
        LOG.info { this.context.args }
        LOG.info { this.context.args.inputFiles }

        val sourceApk = this.context.args.inputFiles.firstOrNull()
        if (sourceApk == null || !sourceApk.exists()) {
            LOG.error { "No APK file found" }
            return
        }
        RevancedResolver.createPatcher(sourceApk, this.context.files().pluginTempDir.toFile())

        LOG.info { "Revanced fingerprint plugin is enabled" }
        this.context.guiContext?.let {

            RevancedFingerprintPluginUi.init(this.context)
        }
    }
}

fun main() {
    println(ReflectionUtils.dexToJavaName("Lcom/datatheorem/android/trustkit/config/DomainPinningPolicy;"))
//    RevancedResolver.createPatcher(File("test.apk"), File("build"))
//    showScriptPanel()

}