package com.github.kr328.clash.common.util

import com.github.kr328.clash.common.log.Log
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.representer.Representer
import java.io.File
import java.nio.charset.StandardCharsets

object YamlUtils {
    fun resetRaw(configFile: File) {
        val rawFile = File(configFile.absolutePath + ".raw")
        if (rawFile.exists()) {
            rawFile.delete()
        }
    }

    private fun createYaml(): Yaml {
        val loaderOptions = LoaderOptions()
        loaderOptions.maxAliasesForCollections = 5000
        
        val dumperOptions = DumperOptions()
        dumperOptions.defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        dumperOptions.isPrettyFlow = true
        
        return Yaml(SafeConstructor(loaderOptions), Representer(dumperOptions), dumperOptions, loaderOptions)
    }

    fun mergeYaml(aFile: File, configFile: File) {
        if (!aFile.exists() || !configFile.exists()) return

        val yaml = createYaml()

        try {
            // 1. Load a.yaml (The overrides)
            val aContent = aFile.readText(StandardCharsets.UTF_8).let {
                @Suppress("UNCHECKED_CAST")
                yaml.load(it) as? Map<String, Any?>
            } ?: return

            // 2. Handle the "raw" backup of config.yaml to ensure we always merge into a clean base
            val rawFile = File(configFile.absolutePath + ".raw")
            if (!rawFile.exists()) {
                configFile.copyTo(rawFile)
            }

            // 3. Load from the RAW file (the original base) instead of the potentially already-merged configFile
            val originalSize = rawFile.length()
            val configContentRaw: Any? = rawFile.readText(StandardCharsets.UTF_8).let {
                yaml.load(it)
            }

            val configContent = when (configContentRaw) {
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    configContentRaw.toMutableMap() as MutableMap<String, Any?>
                }
                null -> {
                    if (originalSize > 5) {
                        Log.w("YamlUtils: config.yaml.raw is $originalSize bytes but parsed as null. Aborting.")
                        return
                    }
                    mutableMapOf()
                }
                else -> {
                    Log.w("YamlUtils: config.yaml.raw root is not a map, skipping.")
                    return
                }
            }

            // 4. Perform merge (a.yaml overrides/adds to the raw config)
            val merged = mergeMaps(aContent, configContent)
            
            // 5. Write back to the ACTIVE configFile
            configFile.writeText(yaml.dump(merged), StandardCharsets.UTF_8)
        } catch (e: Throwable) {
            Log.w("YamlUtils: Merge failed, original file preserved. Error: ${e.javaClass.simpleName} - ${e.message}", e)
            
            throw e
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun mergeMaps(source: Map<String, Any?>, target: MutableMap<String, Any?>): Map<String, Any?> {
        for ((key, sourceValue) in source) {
            val targetValue = target[key]

            when {
                (sourceValue is List<*>) && (targetValue is List<*>) -> {
                    val mergedList = targetValue.toMutableList()
                    
                    // Convert to a fresh List to avoid any IOBE from view manipulation during iteration
                    val items = sourceValue.toList()
                    
                    items.asReversed().forEach { item ->
                        if (item is Map<*, *>) {
                            val name = item["name"]
                            if (name != null) {
                                mergedList.removeAll { (it is Map<*, *>) && (it["name"] == name) }
                            }
                        } else {
                            mergedList.remove(item)
                        }
                        mergedList.add(0, item)
                    }
                    
                    target[key] = mergedList
                }
                (sourceValue is Map<*, *>) && (targetValue is Map<*, *>) -> {
                    val newMap = (targetValue as Map<String, Any?>).toMutableMap()
                    mergeMaps(sourceValue as Map<String, Any?>, newMap)
                    target[key] = newMap
                }
                else -> {
                    target[key] = sourceValue
                }
            }
        }
        return target
    }
}
