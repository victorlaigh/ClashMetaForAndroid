package com.github.kr328.clash

import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.common.util.uuid
import com.github.kr328.clash.design.PropertiesDesign
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.common.util.YamlUtils
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.pendingDir
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.io.File
import com.github.kr328.clash.design.R

class PropertiesActivity : BaseActivity<PropertiesDesign>() {
    private var canceled: Boolean = false
    private lateinit var original: Profile

    override suspend fun main() {
        setResult(RESULT_CANCELED)

        val uuid = intent.uuid ?: return finish()
        val design = PropertiesDesign(this)

        original = withProfile { queryByUUID(uuid) } ?: return finish()

        design.profile = original

        design.yamlContent = withContext(Dispatchers.IO) {
            val pending = pendingDir.resolve(uuid.toString()).resolve("a.yaml")
            val imported = importedDir.resolve(uuid.toString()).resolve("a.yaml")
            
            if (pending.exists()) pending.readText()
            else if (imported.exists()) imported.readText()
            else ""
        }

        setContentDesign(design)

        defer {
            canceled = true

            withProfile { release(uuid) }
        }

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStop -> {
                            val profile = design.profile

                            if (!canceled && profile != original) {
                                withProfile {
                                    patch(profile.uuid, profile.name, profile.source, profile.interval, profile.ageSecretKey)
                                }
                            }
                        }
                        Event.ServiceRecreated -> {
                            finish()
                        }
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        PropertiesDesign.Request.BrowseFiles -> {
                            startActivity(FilesActivity::class.intent.setUUID(uuid))
                        }
                        PropertiesDesign.Request.Commit -> {
                            design.verifyAndCommit()
                        }
                        is PropertiesDesign.Request.SaveYaml -> {
                            val content = it.content
                            withContext(Dispatchers.IO) {
                                try {
                                    val pending = pendingDir.resolve(uuid.toString())
                                    val imported = importedDir.resolve(uuid.toString())

                                    if (pending.exists()) {
                                        val aFile = pending.resolve("a.yaml")
                                        val configFile = pending.resolve("config.yaml")
                                        
                                        aFile.writeText(content)
                                        YamlUtils.mergeYaml(aFile, configFile)
                                    }
                                    if (imported.exists()) {
                                        val aFile = imported.resolve("a.yaml")
                                        val configFile = imported.resolve("config.yaml")

                                        aFile.writeText(content)
                                        YamlUtils.mergeYaml(aFile, configFile)
                                    }

                                    withContext(Dispatchers.Main) {
                                        design.showToast(R.string.saved_to_a_yaml, ToastDuration.Short)
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        design.showExceptionToast(e)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onBackPressed() {
        design?.apply {
            launch {
                if (!progressing) {
                    if (original == profile || requestExitWithoutSaving())
                        finish()
                }
            }
        } ?: return super.onBackPressed()
    }

    private suspend fun PropertiesDesign.verifyAndCommit() {
        when {
            profile.name.isBlank() -> {
                showToast(R.string.empty_name, ToastDuration.Long)
            }
            profile.type != Profile.Type.File && profile.source.isBlank() -> {
                showToast(R.string.invalid_url, ToastDuration.Long)
            }
            else -> {
                try {
                    withProcessing { updateStatus ->
                        withProfile {
                            patch(profile.uuid, profile.name, profile.source, profile.interval, profile.ageSecretKey)

                            coroutineScope {
                                commit(profile.uuid) {
                                    launch {
                                        updateStatus(it)
                                    }
                                }
                                
                                withContext(Dispatchers.IO) {
                                    val imported = importedDir.resolve(profile.uuid.toString())
                                    if (imported.exists()) {
                                        val aFile = imported.resolve("a.yaml")
                                        val configFile = imported.resolve("config.yaml")
                                        
                                        YamlUtils.resetRaw(configFile)
                                        if (aFile.exists()) {
                                            YamlUtils.mergeYaml(aFile, configFile)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    setResult(RESULT_OK)

                    finish()
                } catch (e: Exception) {
                    showExceptionToast(e)
                }
            }
        }
    }
}