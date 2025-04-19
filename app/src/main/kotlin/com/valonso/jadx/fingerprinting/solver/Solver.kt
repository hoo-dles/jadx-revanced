package com.valonso.jadx.fingerprinting.solver

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.valonso.jadx.fingerprinting.RevancedFingerprintPlugin
import io.github.oshai.kotlinlogging.KotlinLogging

data class SolverSettings(
    val useReturnType: Boolean = true,
    val useParameters: Boolean = true,
    val useStrings: Boolean = true,
    val useAccessFlags: Boolean = true,
    val useOpcodes: Boolean = false,
)

object Solver {
    private val LOG = KotlinLogging.logger("${RevancedFingerprintPlugin.ID}/solver")
    private lateinit var methods: List<Method>
    private var solverSettings: SolverSettings = SolverSettings()
    private var methodFeatureList = mutableMapOf<String, List<String>>()

    // State for finding all minimal sets
    private val allMinimalSets = mutableListOf<List<String>>()
    private var currentMinSize = Int.MAX_VALUE

    fun setSettings(settings: SolverSettings = SolverSettings()) {
        this.solverSettings = settings
        LOG.info { "Solver settings updated: $settings" }
    }

    fun setMethods(methods: List<Method>) {
        this.methods = methods
        this.methodFeatureList.clear() // Clear previous features if methods are reset
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


    // Helper to build the initial coverage map
    private fun buildCoverageMap(
        targetMethodFeatures: List<String>,
        otherMethods: Map<String, List<String>>
    ): Map<String, Set<String>> {
        val coverage = mutableMapOf<String, MutableSet<String>>()
        // Initialize coverage map only with features present in the target method
        targetMethodFeatures.forEach { feat ->
            coverage[feat] = mutableSetOf()
        }
        val otherMethodIds = otherMethods.keys
        otherMethods.forEach { (otherId, otherFeatures) ->
            val otherFeaturesSet = otherFeatures.toSet()
            targetMethodFeatures.forEach { targetFeat ->
                // Feature distinguishes if the other method lacks it
                if (!otherFeaturesSet.contains(targetFeat)) {
                    coverage[targetFeat]?.add(otherId) // Add otherId to the set of methods distinguished by targetFeat
                }
            }
        }
        LOG.info { "Built initial coverage map with ${coverage.size} features for the target method." }
        return coverage
    }


    private fun findSetsRecursive(
        initialCoverage: Map<String, Set<String>>, // Full coverage map for target features
        uncovered: MutableSet<String>,
        currentFeatures: MutableList<String>
    ) {
        // Base case: all methods are covered
        if (uncovered.isEmpty()) {
            // Found a potential solution
            if (currentFeatures.size < currentMinSize) {
                // Found a new globally minimal solution
                currentMinSize = currentFeatures.size
                allMinimalSets.clear()
                allMinimalSets.add(currentFeatures.toList()) // Add a copy
                LOG.info { "Found new minimal set (size $currentMinSize): ${currentFeatures.joinToString()}" }
            } else if (currentFeatures.size == currentMinSize) {
                val currentFeaturesList = currentFeatures.toList()
                if (allMinimalSets.none { it.containsAll(currentFeaturesList) && currentFeaturesList.containsAll(it) }) {
                    allMinimalSets.add(currentFeaturesList) // Add a copy
                    LOG.info { "Found another minimal set (size ${currentFeatures.size}): ${currentFeatures.joinToString()}" }
                }
            }
            // If currentFeatures.size > currentMinSize, just ignore it.
            return
        }

        // Pruning: If we've already added more features than the current minimum, stop exploring this path
        if (currentFeatures.size >= currentMinSize) {
            return
        }

        // Find features covering the most *currently* uncovered methods
        // Consider only features from the initialCoverage (target method's features)
        val eligibleFeaturesCoverage = initialCoverage
            .mapValues { (_, coveringSet) -> coveringSet.intersect(uncovered) } // How many *remaining* uncovered methods does each feature cover?
            .filterValues { it.isNotEmpty() } // Keep only features that cover at least one remaining method

        if (eligibleFeaturesCoverage.isEmpty()) {
            // This path is a dead end, cannot cover the remaining methods with available features.
            LOG.info { "Dead end path: Cannot distinguish from ${uncovered.size} methods (${uncovered.joinToString()}) with features ${currentFeatures.joinToString()}" }
            return // Backtrack
        }

        // Find the maximum number of uncovered methods covered by any single eligible feature
        val maxCoverage = eligibleFeaturesCoverage.values.maxOfOrNull { it.size } ?: 0
        if (maxCoverage == 0) { // Should be covered by isEmpty check, but safety belt
            LOG.info { "Dead end path: Max coverage is 0." }
            return // Backtrack
        }

        // Get all features that achieve this maximum coverage
        val bestFeatures = eligibleFeaturesCoverage.filterValues { it.size == maxCoverage }.keys
        LOG.info { "Iteration (depth ${currentFeatures.size}): ${uncovered.size} uncovered. Best features (cover $maxCoverage): $bestFeatures" }


        // Explore paths for each best feature
        for (bestFeature in bestFeatures) {
            // Avoid adding the same feature twice in a single path (doesn't help)
            if (currentFeatures.contains(bestFeature)) continue

            val newlyCovered = eligibleFeaturesCoverage[bestFeature]!! // Safe due to prior checks
            val nextUncovered = uncovered.toMutableSet() // Create a copy for the recursive call
            nextUncovered.removeAll(newlyCovered)

            currentFeatures.add(bestFeature) // Add feature for this path
            findSetsRecursive(initialCoverage, nextUncovered, currentFeatures)
            currentFeatures.removeLast() // Backtrack: remove the feature to explore other paths/sibling choices
        }
    }

    fun getMethodFeatures(methodId: String): List<String> {
        return methodFeatureList[methodId] ?: emptyList()
    }


    /**
     * Finds all sets of features that uniquely distinguish the target method
     * from all other methods using the minimum number of features possible.
     * Uses a backtracking approach to explore different combinations.
     *
     * @param methodId The unique identifier of the target method.
     * @param extraFeatureAmount The number of extra features more than the minimum to include in the search.
     * @return The first minimal distinguishing feature set found.
     * @throws IllegalArgumentException if the methodId is not found.
     * @throws IllegalStateException if the target method cannot be distinguished from all others.
     */
    fun getMinimalDistinguishingFeatures(
        methodId: String
    ): MutableList<List<String>> {
        LOG.info { "Getting minimal distinguishing features for method ID: $methodId" }
        LOG.info { "Current settings: $solverSettings" }
        val targetMethodFeatures = methodFeatureList[methodId]
            ?: throw IllegalArgumentException("Method ID not found: $methodId")
        val otherMethods = methodFeatureList.filterKeys { it != methodId }
        LOG.info { "Target method features:\n\t-> ${targetMethodFeatures.joinToString("\n\t-> ")}" }
        LOG.info { "There are ${otherMethods.size} other methods to compare against." }

        // Build initial coverage map: feature -> set of other method ids distinguished by this feature
        val initialCoverage = buildCoverageMap(targetMethodFeatures, otherMethods)
        val otherMethodIds = otherMethods.keys

        // --- First Run: Find initial minimal sets ---
        LOG.info { "Starting first search for minimal sets..." }
        allMinimalSets.clear()
        currentMinSize = Int.MAX_VALUE
        findSetsRecursive(
            initialCoverage,
            otherMethodIds.toMutableSet(),
            mutableListOf()
        )

        // Check results of the first run
        if (allMinimalSets.isEmpty()) {
            LOG.error { "Error: Could not find any set of features to uniquely distinguish target method '$methodId' from all others using the current settings." }
            throw IllegalStateException(
                "Could not distinguish target method '$methodId' from all other methods with the current feature settings. Try enabling more feature types (return type, parameters, strings, access flags)."
            )
        }

        val firstMinimalSets = allMinimalSets.toList() // Store the results of the first run
        val firstMinSize = currentMinSize
        LOG.info { "First search found ${firstMinimalSets.size} minimal distinguishing feature set(s) with minimum size $firstMinSize:" }
        firstMinimalSets.forEachIndexed { index, set ->
            LOG.info { "  Set ${index + 1}: ${set.joinToString()}" }
        }

        // --- Second Run: Find extra sets excluding features from the first run ---
        LOG.info { "Starting second search for extra sets, excluding features from the first minimal sets..." }
        val featuresToExclude = firstMinimalSets.flatten().toSet()
        LOG.info { "Excluding ${featuresToExclude.size} features: ${featuresToExclude.joinToString()}" }

        val filteredCoverage = initialCoverage.filterKeys { it !in featuresToExclude }

        if (filteredCoverage.isEmpty()) {
            LOG.warn { "No features remaining after excluding those from the first minimal sets. Cannot perform second search." }
            return firstMinimalSets.toMutableList() // Return only the results from the first run
        }

        // Reset state for the second search
        allMinimalSets.clear()
        currentMinSize = Int.MAX_VALUE

        findSetsRecursive(
            filteredCoverage, // Use the filtered coverage map
            otherMethodIds.toMutableSet(),
            mutableListOf()
        )

        val extraMinimalSets = allMinimalSets.toList() // Store results of the second run

        if (extraMinimalSets.isNotEmpty()) {
            LOG.info { "Second search found ${extraMinimalSets.size} additional minimal distinguishing feature set(s) (using remaining features) with minimum size $currentMinSize:" }
            extraMinimalSets.forEachIndexed { index, set ->
                LOG.info { "  Extra Set ${index + 1}: ${set.joinToString()}" }
            }
            // Combine results, ensuring no duplicates (though unlikely given the exclusion)
            val combinedResults = (firstMinimalSets + extraMinimalSets).distinctBy { it.toSet() }
            LOG.info { "Total unique distinguishing sets found: ${combinedResults.size}" }
            return combinedResults.toMutableList()
        } else {
            LOG.info { "Second search did not find any additional distinguishing sets using the remaining features." }
            return firstMinimalSets.toMutableList() // Return only the results from the first run
        }
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
                if (index < parametersList.size) { // Bounds check
                    parametersList[index] = value // Fill in the values from the map
                } else {
                    LOG.error { "Parameter index $index out of bounds for map $map" }
                }
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
                val separator = if (strings.size > 2) ",\n\t\t" else ", "
                strings.joinToString(separator) { str ->
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
        }
        fingerprintString.append("}")

        return fingerprintString.toString()

    }
}