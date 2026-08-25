package com.github.kr328.clash.service

import android.content.Context
import com.github.kr328.clash.service.data.Database
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.data.Pending
import com.github.kr328.clash.service.data.PendingDao
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.remote.IFetchObserver
import com.github.kr328.clash.service.remote.IProfileManager
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.directoryLastModified
import com.github.kr328.clash.service.util.generateProfileUUID
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.pendingDir
import com.github.kr328.clash.service.util.sendProfileChanged
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.util.componentName
import com.github.kr328.clash.common.util.setUUID
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.util.*

class ProfileManager(private val context: Context) : IProfileManager,
    CoroutineScope by CoroutineScope(Dispatchers.IO) {
    private val store = ServiceStore(context)

    init {
        launch {
            Database.database //.init

            bootstrap()

            ProfileReceiver.rescheduleAll(context)
        }
    }

    private suspend fun bootstrap() {
        val imported = ImportedDao().queryAllUUIDs()
        val active = queryActive()

        Log.d("ProfileManager: bootstrap started, imported count = ${imported.size}, active = ${active?.uuid}")

        if (imported.isEmpty() && active == null) {
            val url = context.getString(R.string.default_profile_url)
            val name = context.getString(R.string.default_profile_name)

            Log.d("ProfileManager: default url = '$url'")

            if (url.isNotBlank()) {
                val all = queryAll()
                val targetProfile = all.find { it.source == url }
                val uuid = targetProfile?.uuid ?: create(Profile.Type.Url, name, url)

                Log.d("ProfileManager: using profile $uuid")

                val aYamlContent = context.getString(R.string.default_a_yaml_content)
                if (aYamlContent.isNotBlank()) {
                    context.pendingDir.resolve(uuid.toString()).resolve("a.yaml").writeText(aYamlContent)
                }

                val profile = queryByUUID(uuid)
                if (profile != null) {
                    setActive(profile)
                    Log.d("ProfileManager: set active default profile")
                    
                    // Trigger silent auto-download
                    update(uuid)
                    Log.d("ProfileManager: triggered silent auto-download for $uuid")
                }
            }
        }
    }

    override suspend fun create(type: Profile.Type, name: String, source: String, ageSecretKey: String?): UUID {
        val uuid = generateProfileUUID()
        val pending = Pending(
            uuid = uuid,
            name = name,
            type = type,
            source = source,
            interval = 0,
            upload = 0,
            total = 0,
            download = 0,
            expire = 0,
            ageSecretKey = ageSecretKey,
        )

        PendingDao().insert(pending)

        context.pendingDir.resolve(uuid.toString()).apply {
            deleteRecursively()
            mkdirs()

            @Suppress("BlockingMethodInNonBlockingContext")
            resolve("config.yaml").createNewFile()
            resolve("providers").mkdir()
        }

        return uuid
    }

    override suspend fun clone(uuid: UUID): UUID {
        val newUUID = generateProfileUUID()

        val imported = ImportedDao().queryByUUID(uuid)
            ?: throw FileNotFoundException("profile $uuid not found")

        val pending = Pending(
            uuid = newUUID,
            name = imported.name,
            type = Profile.Type.File,
            source = imported.source,
            interval = imported.interval,
            upload = imported.upload,
            total = imported.total,
            download = imported.download,
            expire = imported.expire,
            ageSecretKey = imported.ageSecretKey
        )

        cloneImportedFiles(uuid, newUUID)

        PendingDao().insert(pending)

        return newUUID
    }

    override suspend fun patch(uuid: UUID, name: String, source: String, interval: Long, ageSecretKey: String?) {
        val pending = PendingDao().queryByUUID(uuid)

        if (pending == null) {
            val imported = ImportedDao().queryByUUID(uuid)
                ?: throw FileNotFoundException("profile $uuid not found")

            cloneImportedFiles(uuid)

            PendingDao().insert(
                Pending(
                    uuid = imported.uuid,
                    name = name,
                    type = imported.type,
                    source = source,
                    interval = interval,
                    upload = 0,
                    total = 0,
                    download = 0,
                    expire = 0,
                    ageSecretKey = ageSecretKey,
                )
            )
        } else {
            val newPending = pending.copy(
                name = name,
                source = source,
                interval = interval,
                upload = 0,
                total = 0,
                download = 0,
                expire = 0,
                ageSecretKey = ageSecretKey,
            )

            PendingDao().update(newPending)
        }
    }

    override suspend fun update(uuid: UUID) {
        scheduleUpdate(uuid, true)
    }

    override suspend fun commit(uuid: UUID, callback: IFetchObserver?) {
        ProfileProcessor.apply(context, uuid, callback)

        scheduleUpdate(uuid, false)
    }

    override suspend fun release(uuid: UUID) {
        ProfileProcessor.release(context, uuid)
    }

    override suspend fun delete(uuid: UUID) {
        ImportedDao().queryByUUID(uuid)?.also {
            ProfileReceiver.cancelNext(context, it)
        }

        ProfileProcessor.delete(context, uuid)
    }

    override suspend fun queryByUUID(uuid: UUID): Profile? {
        return resolveProfile(uuid)
    }

    override suspend fun queryAll(): List<Profile> {
        val uuids = withContext(Dispatchers.IO) {
            (ImportedDao().queryAllUUIDs() + PendingDao().queryAllUUIDs()).distinct()
        }

        return uuids.mapNotNull { resolveProfile(it) }
    }

    override suspend fun queryActive(): Profile? {
        val active = store.activeProfile ?: return null

        return if (ImportedDao().exists(active) || PendingDao().exists(active)) {
            resolveProfile(active)
        } else {
            null
        }
    }

    override suspend fun setActive(profile: Profile) {
        ProfileProcessor.active(context, profile.uuid)
    }

    private suspend fun resolveProfile(uuid: UUID): Profile? {
        val imported = ImportedDao().queryByUUID(uuid)
        val pending = PendingDao().queryByUUID(uuid)

        if (imported == null && pending == null) return null

        val active = store.activeProfile
        val name = pending?.name ?: imported?.name ?: ""
        val type = pending?.type ?: imported?.type ?: Profile.Type.File
        val source = pending?.source ?: imported?.source ?: ""
        val interval = pending?.interval ?: imported?.interval ?: 0
        val upload = pending?.upload ?: imported?.upload ?: 0
        val download = pending?.download ?: imported?.download ?: 0
        val total = pending?.total ?: imported?.total ?: 0
        val expire = pending?.expire ?: imported?.expire ?: 0

        return Profile(
            uuid = uuid,
            name = name,
            type = type,
            source = source,
            active = active != null && (imported?.uuid == active || pending?.uuid == active),
            interval = interval,
            upload = upload,
            download = download,
            total = total,
            expire = expire,
            updatedAt = resolveUpdatedAt(uuid),
            imported = imported != null,
            pending = pending != null,
            ageSecretKey = pending?.ageSecretKey ?: imported?.ageSecretKey,
        )
    }

    private fun resolveUpdatedAt(uuid: UUID): Long {
        return context.pendingDir.resolve(uuid.toString()).directoryLastModified
            ?: context.importedDir.resolve(uuid.toString()).directoryLastModified
            ?: -1
    }

    private fun cloneImportedFiles(source: UUID, target: UUID = source) {
        val s = context.importedDir.resolve(source.toString())
        val t = context.pendingDir.resolve(target.toString())

        if (!s.exists())
            throw FileNotFoundException("profile $source not found")

        t.deleteRecursively()

        s.copyRecursively(t)
    }

    private suspend fun scheduleUpdate(uuid: UUID, startImmediately: Boolean) {
        val imported = ImportedDao().queryByUUID(uuid)

        if (startImmediately) {
            val intent = Intent(Intents.ACTION_PROFILE_REQUEST_UPDATE)
                .setComponent(ProfileReceiver::class.componentName)
                .setUUID(uuid)

            context.sendBroadcast(intent)
        } else {
            if (imported != null) {
                ProfileReceiver.scheduleNext(context, imported)
            }
        }
    }
}
