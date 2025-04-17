package com.valonso.jadx.fingerprinting

import app.revanced.patcher.Fingerprint
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.platform.commons.logging.Logger

class ScriptEvaluationTest {
    @ParameterizedTest
    @ValueSource(
        strings = [
            """
            fingerprint {
                returns("V")
                accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
                parameters("Landroid/view/MenuItem;")
                custom { method, _ ->
                    true
                }
            }
        """,
            """
           fingerprint {
                accessFlags(AccessFlags.PUBLIC, AccessFlags.STATIC)
                returns("L")
                parameters("L")
                opcodes(
                    Opcode.IGET_OBJECT,
                    Opcode.GOTO,
                    Opcode.CONST_STRING,
                )
                // Instead of applying a bytecode patch, it might be possible to only rely on code from the extension and
                // manually set the desired version string as this keyed value in the SharedPreferences.
                // But, this bytecode patch is simple and it works.
                strings("pref_override_build_version_name")
            }
        """,
            """
            import app.revanced.patcher.*
            fingerprint {
            strings("test")
            }
        """
        ]
    )
    fun evaluateFingerprintString(fingerprint: String) {
        val result = ScriptEvaluation.evaluateFingerprintString(fingerprint.trimIndent())
        assertInstanceOf(
            Fingerprint::class.java,
            result
        )
    }

}