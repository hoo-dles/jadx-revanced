package com.valonso.jadx.fingerprinting

import com.android.tools.smali.dexlib2.iface.Method
import io.github.oshai.kotlinlogging.KotlinLogging

private val UTIL_LOG = KotlinLogging.logger("${RevancedFingerprintPlugin.ID}/utils")
fun Method.getShortId(): String {
//    shortId: <init>(Ljava/lang/String;Ljava/lang/Boolean;Ljava/util/Set;Ljava/lang/Boolean;Ljava/util/Date;Ljava/util/Set;Ljava/lang/Boolean;)V

    return "${this.name}(${this.parameterTypes.joinToString(separator = "") { it.toString() }})${this.returnType}"
}
fun testFingerprints() {

}
