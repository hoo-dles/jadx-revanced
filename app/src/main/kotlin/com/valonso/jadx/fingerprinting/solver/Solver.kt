package com.valonso.jadx.fingerprinting.solver

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.valonso.jadx.fingerprinting.RevancedFingerprintPlugin
import io.github.oshai.kotlinlogging.KotlinLogging

data class SolverSettings(
    val useReturnType: Boolean = true,
    val useParameters: Boolean = true,
    val useStrings: Boolean = true,
    val useAccessFlags: Boolean = true
)

object Solver {
    private val LOG = KotlinLogging.logger("${RevancedFingerprintPlugin.ID}/solver")
    private lateinit var methods: List<Method>
    private var solverSettings: SolverSettings = SolverSettings()
    private var methodFeatureList = mutableMapOf<String, List<String>>()

    fun setMethods(methods: List<Method>) {
        this.methods = methods
        this.methods.forEach { method ->
            val features = extractFeatures(method)
            methodFeatureList[method.getUniqueId()] = features
        }
        LOG.info { "Extracted features for ${this.methods.size} methods." }
    }

    fun extractFeatures(method: Method): List<String> {
        val fingerprint = method.toMethodFingerprint()
        val features = mutableListOf<String>()
        if (solverSettings.useReturnType) {
            features.add("returnType|${fingerprint.returnType}")
        }
        if (solverSettings.useParameters) {
            features.addAll(fingerprint.parameters.mapIndexed { index, param ->
                "parameter_$index|$param"
            })
        }
        if (solverSettings.useStrings) {
            features.addAll(fingerprint.strings.map { "strings|$it" })
        }
        if (solverSettings.useAccessFlags) {
            features.add("accessFlags|${fingerprint.accessFlags}")
        }
        return features
    }

    fun getMinimalDistinguishingFeatures(
        methodId: String,
    ): List<String> {
        LOG.info { "Getting minimal distinguishing features for method ID: $methodId" }
        LOG.info { "Current settings: $solverSettings" }
        val targetMethodFeatures = methodFeatureList[methodId]
            ?: throw IllegalArgumentException("Method ID not found: $methodId")
        val otherMethods = methodFeatureList.filterKeys { it != methodId }
        LOG.info { "Target method features:\n ${targetMethodFeatures.joinToString("\n\t-> ")}" }
        LOG.info { "There are ${otherMethods.size} other methods to compare against." }

        // Build coverage map: feature -> set of other method ids distinguished by this feature
        val coverage = mutableMapOf<String, MutableSet<String>>()
        targetMethodFeatures.forEach { feat ->
            coverage[feat] = mutableSetOf()
        }

        val otherMethodIds = otherMethods.keys

        otherMethods.forEach { (otherId, otherFeatures) ->
            val otherFeaturesSet = otherFeatures.toSet() // Use Set for efficient lookups
            targetMethodFeatures.forEach { targetFeat ->
                // Feature distinguishes if the other method lacks it
                if (!otherFeaturesSet.contains(targetFeat)) {
                    coverage[targetFeat]?.add(otherId)
                }
            }
        }

        // Greedy set cover
        val uncovered = otherMethodIds.toMutableSet()
        val selectedFeatures = mutableListOf<String>()
        var iteration = 0
        while (uncovered.isNotEmpty()) {
            LOG.info { "There are ${uncovered.size} uncovered methods, iteration $iteration" }
            // Find features that cover at least one uncovered method and calculate how many *uncovered* methods they cover
            val eligibleFeaturesCoverage = coverage
                .mapValues { (_, coveringSet) -> coveringSet.intersect(uncovered) } // Intersect with current uncovered
                .filterValues { it.isNotEmpty() } // Keep only those that cover at least one *remaining* uncovered method

            if (eligibleFeaturesCoverage.isEmpty()) {
                // No feature can cover remaining methods: stop
                LOG.error { "\nWarning: Could not distinguish target from ${uncovered.size} other methods with current settings:" }
                uncovered.forEach { id ->
                    LOG.error { " - $id" }
                }
                throw IllegalStateException(
                    "Could not distinguish target method from ${uncovered.size} other methods with a simple fingerprint. \n${
                        featuresToFingerprintString(
                            selectedFeatures
                        )
                    }"
                )
            }

            // Pick feature that covers the most *remaining* uncovered methods
            val bestFeature = eligibleFeaturesCoverage.maxByOrNull { it.value.size }?.key
                ?: break // Should not happen if eligibleFeaturesCoverage is not empty, but safe break

            val newlyCovered = eligibleFeaturesCoverage[bestFeature]!! // Safe due to prior checks

            LOG.info { "Selected feature '$bestFeature', covers ${newlyCovered.size} methods" } // Debug print

            selectedFeatures.add(bestFeature)
            uncovered.removeAll(newlyCovered)
            iteration++
            // Optional: Remove the chosen feature from consideration for the next round
            // coverage.remove(bestFeature)
        }

        LOG.info { "Selected features: $selectedFeatures" }

        return selectedFeatures
    }


    fun featuresToFingerprintString(featureList: List<String>): String {
        LOG.info { "Converting features to fingerprint string: $featureList" }

        val fingerprintString = StringBuilder("fingerprint {")
        val fingerprintParts = mutableListOf<String>()
        val strings = mutableListOf<String>()
        val parametersMap = mutableMapOf<Int, String>()
        featureList.forEach { feature ->
            val parts = feature.split("|", limit = 2)
            if (parts.size != 2) {
                LOG.info { "Invalid feature format: $feature" }
                return@forEach
            }
            val featureName = parts[0]
            val featureValue = parts[1]
            when (featureName) {
                "returnType" -> fingerprintParts.add("returns(\"$featureValue\")")
                "strings" -> strings.add(featureValue)
                "accessFlags" -> {
                    val accessFlags = AccessFlags.getAccessFlagsForMethod(featureValue.toInt())
                    val strBuilder = StringBuilder()
                    strBuilder.append("accessFlags(")
                    accessFlags.joinToString(", ") { flag ->
                        "AccessFlags.${flag.name}"
                    }.let { flags ->
                        strBuilder.append(flags)
                    }
                    strBuilder.append(")")
                    fingerprintParts.add(strBuilder.toString())
                }

                else -> {
                    if (featureName.startsWith("parameter_")) {
                        val indexStr = featureName.substringAfter("parameter_")
                        val index = indexStr.toIntOrNull()
                        if (index != null) {
                            parametersMap[index] = featureValue
                        } else {
                            LOG.error { "Invalid parameter index in feature: $featureName" }
                        }
                    } else {
                        LOG.info { "Unknown feature type: $featureName" }
                    }
                }
            }
        }
        parametersMap.takeIf { it.isNotEmpty() }?.let { map ->
            val maxIndex = map.keys.maxOrNull() ?: -1
            val parametersList = MutableList(maxIndex + 1) { "" } // Initialize with empty strings
            map.forEach { (index, value) ->
                parametersList[index] = value // Fill in the values from the map
            }

            StringBuilder().let { builder ->
                builder.append("parameters(")
                parametersList.joinToString(", ") { param ->
                    "\"$param\"" // Quote each parameter, including empty ones
                }.let { paramList ->
                    builder.append(paramList)
                }
                builder.append(")")
                fingerprintParts.add(builder.toString())
            }
        }

        strings.takeIf { it.isNotEmpty() }?.let { strings ->
            StringBuilder().let { builder ->
                builder.append("strings(")
                strings.joinToString(", ") { str ->
                    "\"$str\""
                }.let { strList ->
                    builder.append(strList)
                }
                builder.append(")")
                fingerprintParts.add(builder.toString())
            }
        }

        fingerprintParts.joinToString("\n\t", prefix = "\n\t", postfix = "\n") { it }.let { parts ->
            fingerprintString.append(parts)
            LOG.info { "Fingerprint string part: $parts" }
        }
        fingerprintString.append("}")

        return fingerprintString.toString()

    }
}