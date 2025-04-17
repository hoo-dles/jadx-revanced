package com.valonso.jadx.fingerprinting

import jadx.api.plugins.options.OptionFlag
import jadx.api.plugins.options.impl.BasePluginOptionsBuilder

class PluginOptions : BasePluginOptionsBuilder() {
    var enabled: Boolean = true
        private set

    override fun registerOptions() {
        boolOption("${RevancedFingerprintPlugin.ID}.enabled")
            .description("Enable ReVanced Fingerprint Plugin")
            .defaultValue(true)
            .setter { v -> enabled = v }
            .flags(OptionFlag.PER_PROJECT, OptionFlag.NOT_CHANGING_CODE)

    }
}
