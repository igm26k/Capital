package com.capital.accounting.imports

import android.content.Context
import android.util.AtomicFile
import com.capital.accounting.api.ApiClient
import com.capital.accounting.auth.StoredSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File

fun StoredSession.notificationBinding() = NotificationBinding(origin,ownerId,workspaceId,generationId,sessionId)

/** Only typed suggestions are saved, in noBackupFilesDir. No notification text, token or SBN key. */
@kotlinx.serialization.Serializable
private data class NotificationConfiguration(val binding:NotificationBinding,val profiles:List<NotificationProfile>)

class NotificationInboxStore(context: Context) {
    private val directory = File(context.noBackupFilesDir,"notification-inbox-v1")
    private val mutex = Mutex()
    val captureErrors = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var cached: NotificationInbox? = null
    @Volatile private var paused=false
    val active:NotificationInbox? get()=if(paused)null else cached
    fun pauseCapture() { paused=true;cached=null }
    private fun file(key: String): AtomicFile {
        require(key.matches(Regex("[0-9a-f]{64}")))
        check(directory.isDirectory || directory.mkdirs())
        return AtomicFile(File(directory,"$key.json"))
    }
    private fun bytes(file: AtomicFile): ByteArray = file.openRead().use { input ->
        val output = java.io.ByteArrayOutputStream(); val buffer=ByteArray(4096)
        while(true) { val n=input.read(buffer); if(n<0)break; require(output.size()+n<=2_097_152); output.write(buffer,0,n) }
        output.toByteArray()
    }
    private fun write(file: AtomicFile, bytes: ByteArray) {
        require(bytes.size<=2_097_152)
        val stream=file.startWrite()
        try { stream.write(bytes);file.finishWrite(stream);check(this.bytes(file).contentEquals(bytes)) }
        catch(e:Exception) { file.failWrite(stream);throw e }
    }
    private fun load(binding: NotificationBinding,inherit:Boolean=true): NotificationInbox {
        val file=file(binding.key)
        val result=try { ApiClient.json.decodeFromString<NotificationInbox>(bytes(file).decodeToString()) }
            catch(_:java.io.FileNotFoundException) { NotificationInbox(binding) }
        require(result.binding==binding && result.format==1 && result.items.size<=1000)
        require(result.configuredProfiles.size<=20 && result.configuredProfiles.map {it.id}.distinct().size==result.configuredProfiles.size)
        result.configuredProfiles.forEach(NotificationParser::validate)
        if(result.configuredProfiles.isNotEmpty()||!inherit)return result
        val configuration=AtomicFile(File(directory,"profiles-${binding.configurationKey}.json"))
        val definitions=try {ApiClient.json.decodeFromString<NotificationConfiguration>(bytes(configuration).decodeToString())}catch(_:java.io.FileNotFoundException){
            val pointer=try{ApiClient.json.decodeFromString<NotificationBinding>(bytes(AtomicFile(File(directory,"active"))).decodeToString())}catch(_:java.io.FileNotFoundException){return result}
            if(pointer.key!=binding.key&&pointer.configurationKey==binding.configurationKey){val previous=load(pointer,false);return result.copy(profile=previous.profile,profiles=previous.configuredProfiles,enabled=false)}
            return result
        }
        require(definitions.binding.configurationKey==binding.configurationKey && definitions.profiles.size<=20)
        definitions.profiles.forEach(NotificationParser::validate)
        require(definitions.profiles.map {it.id}.distinct().size==definitions.profiles.size)
        return result.copy(profile=definitions.profiles.firstOrNull(),profiles=definitions.profiles,enabled=false)
    }
    private fun save(value: NotificationInbox) {
        write(file(value.binding.key),ApiClient.json.encodeToString(value).toByteArray())
        if(active?.binding==value.binding)cached=value
    }
    suspend fun read(binding: NotificationBinding): NotificationInbox = withContext(Dispatchers.IO) { mutex.withLock { load(binding) } }
    suspend fun restoreActive(authorized: suspend (NotificationBinding) -> Boolean) = withContext(Dispatchers.IO) { mutex.withLock {
        cached=null
        val pointer=AtomicFile(File(directory,"active"))
        val binding=try { ApiClient.json.decodeFromString<NotificationBinding>(bytes(pointer).decodeToString()) } catch(_:java.io.FileNotFoundException) { return@withLock }
        if(authorized(binding))cached=load(binding)
    } }
    suspend fun configure(binding: NotificationBinding, profile: NotificationProfile): NotificationInbox = withContext(Dispatchers.IO) { mutex.withLock {
        NotificationParser.validate(profile)
        val prior=load(binding)
        // Do not remap existing receipts to a different bank/account/template.
        val updated=prior.configure(profile)
        save(updated)
        write(AtomicFile(File(directory,"profiles-${binding.configurationKey}.json")),ApiClient.json.encodeToString(NotificationConfiguration(binding,updated.configuredProfiles)).toByteArray())
        write(AtomicFile(File(directory,"active")),ApiClient.json.encodeToString(binding).toByteArray())
        paused=false;cached=updated;updated
    } }
    suspend fun disable(binding: NotificationBinding): NotificationInbox = withContext(Dispatchers.IO) { mutex.withLock {
        val updated=load(binding).copy(enabled=false);save(updated);updated
    } }
    suspend fun capture(binding: NotificationBinding,candidates: List<NotificationCandidate>) = withContext(Dispatchers.IO) { mutex.withLock {
        if(active?.binding!=binding || active?.enabled!=true)return@withLock
        save(if(candidates.isEmpty())load(binding).skipped() else load(binding).acceptMatches(candidates))
    } }
    suspend fun enable(binding:NotificationBinding):NotificationInbox=withContext(Dispatchers.IO){mutex.withLock {
        val updated=load(binding).copy(enabled=true);require(updated.configuredProfiles.any {it.enabled});save(updated)
        write(AtomicFile(File(directory,"profiles-${binding.configurationKey}.json")),ApiClient.json.encodeToString(NotificationConfiguration(binding,updated.configuredProfiles)).toByteArray())
        write(AtomicFile(File(directory,"active")),ApiClient.json.encodeToString(binding).toByteArray());paused=false;cached=updated;updated
    }}
    suspend fun choose(binding:NotificationBinding,id:String,profileId:String):NotificationInbox=withContext(Dispatchers.IO){mutex.withLock {
        val updated=load(binding).choose(id,profileId);save(updated);updated
    }}
    suspend fun uploaded(binding:NotificationBinding,id:String,serverId:String,serverState:String?=null):NotificationInbox=withContext(Dispatchers.IO) {mutex.withLock {
        val original=load(binding)
        if(original.items.none {it.eventId==id})return@withLock original
        val updated=original.uploaded(id,serverId,serverState);save(updated);updated
    } }
    suspend fun manualOnly(binding:NotificationBinding,id:String):NotificationInbox=withContext(Dispatchers.IO){mutex.withLock {
        val updated=load(binding).manualOnly(id);save(updated);updated
    }}
    suspend fun dismiss(binding: NotificationBinding,id:String): NotificationInbox = withContext(Dispatchers.IO) { mutex.withLock {
        val updated=load(binding).dismiss(id);save(updated);updated
    } }
    suspend fun eraseAfterAccountDeletion(saved:StoredSession) = withContext(Dispatchers.IO) { mutex.withLock {
        cached=null
        directory.listFiles()?.mapNotNull { Regex("([0-9a-f]{64})\\.json(?:\\.bak|\\.new)?").matchEntire(it.name)?.groupValues?.get(1) }?.distinct()?.forEach { key ->
            val disk=File(directory,"$key.json")
            val value=ApiClient.json.decodeFromString<NotificationInbox>(bytes(AtomicFile(disk)).decodeToString())
            if(value.binding.origin==saved.origin && value.binding.ownerId==saved.ownerId) {
                AtomicFile(disk).delete()
                check(listOf(disk,File(disk.path+".bak"),File(disk.path+".new")).none { it.exists() })
            }
        }
        directory.listFiles()?.mapNotNull {Regex("(profiles-[0-9a-f]{64}\\.json)(?:\\.bak|\\.new)?").matchEntire(it.name)?.groupValues?.get(1)}?.distinct()?.forEach {name->
            val disk=File(directory,name)
            val value=ApiClient.json.decodeFromString<NotificationConfiguration>(bytes(AtomicFile(disk)).decodeToString())
            if(value.binding.origin==saved.origin&&value.binding.ownerId==saved.ownerId){AtomicFile(disk).delete();check(listOf(disk,File(disk.path+".bak"),File(disk.path+".new")).none {it.exists()})}
        }
        AtomicFile(File(directory,"active")).delete()
    } }
}
