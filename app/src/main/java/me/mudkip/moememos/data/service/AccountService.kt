package me.mudkip.moememos.data.service

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import com.skydoves.sandwich.ApiResponse
import com.skydoves.sandwich.getOrNull
import com.skydoves.sandwich.getOrThrow
import com.skydoves.sandwich.retrofit.adapters.ApiResponseCallAdapterFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.mudkip.moememos.R
import me.mudkip.moememos.data.api.MemosV0Api
import me.mudkip.moememos.data.api.MemosV1Api
import me.mudkip.moememos.data.local.FileStorage
import me.mudkip.moememos.data.local.MoeMemosDatabase
import me.mudkip.moememos.data.local.entity.ResourceEntity
import me.mudkip.moememos.data.model.Account
import me.mudkip.moememos.data.model.LocalAccount
import me.mudkip.moememos.data.model.Memo
import me.mudkip.moememos.data.model.Resource
import me.mudkip.moememos.data.model.User
import me.mudkip.moememos.data.model.UserData
import me.mudkip.moememos.data.model.UserSettings
import me.mudkip.moememos.data.repository.AbstractMemoRepository
import me.mudkip.moememos.data.repository.LocalDatabaseRepository
import me.mudkip.moememos.data.repository.MemosV0Repository
import me.mudkip.moememos.data.repository.MemosV1Repository
import me.mudkip.moememos.data.repository.RemoteRepository
import me.mudkip.moememos.data.repository.SyncingRepository
import me.mudkip.moememos.ext.getErrorMessage
import me.mudkip.moememos.ext.string
import me.mudkip.moememos.ext.settingsDataStore
import net.swiftzer.semver.SemVer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.BufferedOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccountService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val database: MoeMemosDatabase,
    private val fileStorage: FileStorage,
    private val secureTokenStorage: SecureTokenStorage,
) {
    sealed class LoginCompatibility {
        data class Supported(val accountCase: UserData.AccountCase) : LoginCompatibility()
        data class Unsupported(val message: String) : LoginCompatibility()
        data class RequiresConfirmation(
            val accountCase: UserData.AccountCase,
            val version: String,
            val message: String,
        ) : LoginCompatibility()
    }

    sealed class SyncCompatibility {
        object Allowed : SyncCompatibility()
        data class Blocked(val message: String?) : SyncCompatibility()
        data class RequiresConfirmation(val version: String, val message: String) : SyncCompatibility()
    }

    private data class ServerVersionInfo(
        val accountCase: UserData.AccountCase,
        val version: String,
    )

    private data class RemoteExportContext(
        val accountKey: String,
        val host: String,
        val displayName: String,
        val httpClient: OkHttpClient,
        val repository: RemoteRepository,
    )

    private enum class VersionPolicy {
        SUPPORTED,
        TOO_LOW,
        V1_HIGHER,
    }

    private val exportDateFormatter: DateTimeFormatter = DateTimeFormatter
        .ofPattern("yyyyMMdd-HHmmss", Locale.US)
        .withZone(ZoneId.systemDefault())

    private val networkJson = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    @Volatile
    var httpClient: OkHttpClient = okHttpClient
        private set

    val accounts = context.settingsDataStore.data.map { settings ->
        settings.usersList.mapNotNull(::parseAccountWithSecureToken)
    }

    val currentAccount = context.settingsDataStore.data.map { settings ->
        settings.usersList.firstOrNull { it.accountKey == settings.currentUser }
            ?.let(::parseAccountWithSecureToken)
    }

    @Volatile
    private var repository: AbstractMemoRepository = LocalDatabaseRepository(
        database.memoDao(),
        fileStorage,
        Account.Local(LocalAccount())
    )

    @Volatile
    private var remoteRepository: RemoteRepository? = null

    private val mutex = Mutex()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initialization = CompletableDeferred<Unit>()

    init {
        serviceScope.launch {
            try {
                mutex.withLock {
                    updateCurrentAccount(currentAccount.first())
                }
                initialization.complete(Unit)
            } catch (e: Throwable) {
                initialization.completeExceptionally(e)
            }
        }
    }

    private fun updateCurrentAccount(account: Account?) {
        repository.close()
        when (account) {
            null -> {
                this.repository = LocalDatabaseRepository(database.memoDao(), fileStorage, Account.Local(LocalAccount()))
                this.remoteRepository = null
                httpClient = okHttpClient
            }
            is Account.Local -> {
                this.repository = LocalDatabaseRepository(database.memoDao(), fileStorage, account)
                this.remoteRepository = null
                httpClient = okHttpClient
            }
            is Account.MemosV0 -> {
                val (client, memosApi) = createMemosV0Client(account.info.host, account.info.accessToken)
                val remote = MemosV0Repository(memosApi, account)
                this.repository = SyncingRepository(
                    database.memoDao(),
                    fileStorage,
                    remote,
                    account
                ) { user ->
                    updateAccountFromSyncedUser(account.accountKey(), user)
                }
                this.remoteRepository = remote
                this.httpClient = client
            }
            is Account.MemosV1 -> {
                val (client, memosApi) = createMemosV1Client(account.info.host, account.info.accessToken)
                val remote = MemosV1Repository(memosApi, account)
                this.repository = SyncingRepository(
                    database.memoDao(),
                    fileStorage,
                    remote,
                    account
                ) { user ->
                    updateAccountFromSyncedUser(account.accountKey(), user)
                }
                this.remoteRepository = remote
                this.httpClient = client
            }
        }
    }

    suspend fun switchAccount(accountKey: String) {
        awaitInitialization()
        mutex.withLock {
            val account = accounts.first().firstOrNull { it.accountKey() == accountKey }
            context.settingsDataStore.updateData { settings ->
                settings.copy(currentUser = accountKey)
            }
            updateCurrentAccount(account)
        }
    }

    suspend fun addAccount(account: Account) {
        awaitInitialization()
        mutex.withLock {
            persistAccessToken(account)
            context.settingsDataStore.updateData { settings ->
                val users = settings.usersList.toMutableList()
                val index = users.indexOfFirst { it.accountKey == account.accountKey() }
                val currentSettings = users.getOrNull(index)?.settings ?: UserSettings()
                if (index != -1) {
                    users.removeAt(index)
                }
                users.add(account.toPersistedUserData(currentSettings))
                settings.copy(
                    usersList = users,
                    currentUser = account.accountKey(),
                )
            }
            updateCurrentAccount(account)
        }
    }

    suspend fun removeAccount(accountKey: String) {
        awaitInitialization()
        mutex.withLock {
            context.settingsDataStore.updateData { settings ->
                val users = settings.usersList.toMutableList()
                val index = users.indexOfFirst { it.accountKey == accountKey }
                if (index != -1) {
                    users.removeAt(index)
                }
                val newCurrentUser = if (settings.currentUser == accountKey) {
                    users.firstOrNull()?.accountKey ?: ""
                } else {
                    settings.currentUser
                }
                settings.copy(
                    usersList = users,
                    currentUser = newCurrentUser,
                )
            }
            updateCurrentAccount(currentAccount.first())
            purgeAccountData(accountKey)
            secureTokenStorage.removeToken(accountKey)
        }
    }

    suspend fun exportLocalAccountZip(destinationUri: Uri) {
        withContext(Dispatchers.IO) {
            val accountKey = Account.Local().accountKey()
            val memoDao = database.memoDao()
            val memos = memoDao.getAllMemosForSync(accountKey)
                .filterNot { it.isDeleted }
                .sortedWith(compareBy({ it.date }, { it.content }))

            if (memos.isEmpty()) {
                throw IllegalStateException("No local memos to export")
            }

            context.contentResolver.openOutputStream(destinationUri)?.use { output ->
                ZipOutputStream(output).use { zip ->
                    val collisionMap = hashMapOf<String, Int>()
                    for (memo in memos) {
                        val memoBaseName = uniqueMemoBaseName(memo.date, collisionMap)
                        zip.putNextEntry(ZipEntry("$memoBaseName.md"))
                        zip.write(memo.content.toByteArray(Charsets.UTF_8))
                        zip.closeEntry()

                        val resources = memoDao.getMemoResources(memo.identifier, accountKey)
                            .sortedWith(compareBy<ResourceEntity>({ it.filename }, { it.uri }))
                        resources.forEachIndexed { index, resource ->
                            val sourceFile = localFileForResource(resource)
                                ?: throw IllegalStateException("Missing resource file: ${resource.filename}")
                            if (!sourceFile.exists()) {
                                throw IllegalStateException("Missing resource file: ${resource.filename}")
                            }
                            val ext = exportFileExtension(resource, sourceFile)
                            val attachmentName = if (ext.isBlank()) {
                                "$memoBaseName-${index + 1}"
                            } else {
                                "$memoBaseName-${index + 1}.$ext"
                            }
                            zip.putNextEntry(ZipEntry(attachmentName))
                            sourceFile.inputStream().use { input -> input.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                }
            } ?: throw IllegalStateException("Unable to open export destination")
        }
    }

    suspend fun exportMemosAccountForKeerZip(accountKey: String, destinationUri: Uri) {
        withContext(Dispatchers.IO) {
            awaitInitialization()
            val account = accounts.first().firstOrNull { it.accountKey() == accountKey }
                ?: throw IllegalStateException("Account not found")
            val exportContext = buildRemoteExportContext(account)
            val currentUser = requireSuccess(exportContext.repository.getCurrentUser())
            val currentUserId = normalizeUserIdentifier(currentUser.identifier)
                .ifEmpty { normalizeUserIdentifier(exportContext.displayName) }
            val memos = loadPersonalRemoteMemos(exportContext.repository, currentUserId)
            if (memos.isEmpty()) {
                throw IllegalStateException("No personal memos to export")
            }

            context.contentResolver.openOutputStream(destinationUri)?.use { output ->
                ZipOutputStream(BufferedOutputStream(output)).use { zip ->
                    val transferMemos = mutableListOf<KeerMemoTransferMemo>()
                    memos.forEachIndexed { memoIndex, memo ->
                        val transferAttachments = mutableListOf<KeerMemoTransferAttachment>()
                        memo.resources.forEachIndexed { attachmentIndex, resource ->
                            val filename = resolveTransferAttachmentFilename(resource.filename, attachmentIndex)
                            val entryPath = buildTransferAttachmentEntryPath(memoIndex, attachmentIndex, filename)
                            writeRemoteResourceToTransferZip(
                                zip = zip,
                                entryPath = entryPath,
                                resource = resource,
                                exportContext = exportContext,
                            )
                            transferAttachments += KeerMemoTransferAttachment(
                                path = entryPath,
                                filename = filename,
                                mimeType = resource.mimeType,
                            )
                        }
                        transferMemos += KeerMemoTransferMemo(
                            importId = buildRemoteMemoImportId(
                                host = exportContext.host,
                                userId = currentUserId,
                                remoteMemoId = memo.remoteId,
                            ),
                            content = memo.content,
                            createdAt = memo.date.toString(),
                            visibility = memo.visibility.name,
                            tags = memo.tags,
                            pinned = memo.pinned,
                            archived = memo.archived,
                            attachments = transferAttachments,
                        )
                    }

                    val document = KeerMemoTransferDocument(
                        exportedAt = Instant.now().toString(),
                        source = KeerMemoTransferSource(
                            host = exportContext.host,
                            userId = currentUserId.ifBlank { null },
                            username = currentUser.name.ifBlank { exportContext.displayName }.ifBlank { null },
                        ),
                        memos = transferMemos,
                    )
                    val payload = KeerMemoTransferCodec.encode(document)
                    zip.putNextEntry(ZipEntry(keerTransferManifestEntryName))
                    zip.write(payload.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            } ?: throw IllegalStateException("Unable to open export destination")
        }
    }

    private fun uniqueMemoBaseName(date: Instant, collisionMap: MutableMap<String, Int>): String {
        val base = exportDateFormatter.format(date)
        val count = collisionMap[base] ?: 0
        collisionMap[base] = count + 1
        return if (count == 0) base else "${base}_$count"
    }

    private fun buildRemoteExportContext(account: Account): RemoteExportContext {
        return when (account) {
            is Account.MemosV0 -> {
                val (client, api) = createMemosV0Client(account.info.host, account.info.accessToken)
                RemoteExportContext(
                    accountKey = account.accountKey(),
                    host = account.info.host,
                    displayName = account.info.name,
                    httpClient = client,
                    repository = MemosV0Repository(api, account),
                )
            }
            is Account.MemosV1 -> {
                val (client, api) = createMemosV1Client(account.info.host, account.info.accessToken)
                RemoteExportContext(
                    accountKey = account.accountKey(),
                    host = account.info.host,
                    displayName = account.info.name,
                    httpClient = client,
                    repository = MemosV1Repository(api, account),
                )
            }
            is Account.Local -> {
                throw IllegalStateException("Keer export is only available for remote Memos accounts")
            }
        }
    }

    private suspend fun loadPersonalRemoteMemos(
        repository: RemoteRepository,
        currentUserId: String,
    ): List<Memo> {
        val activeMemos = requireSuccess(repository.listMemos())
        val archivedMemos = requireSuccess(repository.listArchivedMemos())
        val merged = LinkedHashMap<String, Memo>()
        (activeMemos + archivedMemos).forEach { memo ->
            merged[memo.remoteId] = memo
        }
        val candidates = merged.values.map { memo ->
            memo to memo.creator?.identifier?.let(::normalizeUserIdentifier).orEmpty()
        }
        val hasCreatorInfo = candidates.any { (_, creatorId) -> creatorId.isNotEmpty() }
        return candidates
            .asSequence()
            .filter { (_, creatorId) ->
                if (hasCreatorInfo && currentUserId.isNotBlank()) creatorId == currentUserId else true
            }
            .map { (memo, _) -> memo }
            .sortedByDescending(Memo::date)
            .toList()
    }

    private suspend fun writeRemoteResourceToTransferZip(
        zip: ZipOutputStream,
        entryPath: String,
        resource: Resource,
        exportContext: RemoteExportContext,
    ) {
        if (writeCachedResourceToTransferZip(zip, entryPath, resource, exportContext.accountKey)) {
            return
        }
        val attachmentLabel = resource.filename.ifBlank { resource.remoteId.ifBlank { "unknown" } }
        val request = Request.Builder()
            .url(resource.uri)
            .get()
            .build()
        exportContext.httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Failed to download attachment \"$attachmentLabel\" (${response.code})")
            }
            @Suppress("SENSELESS_COMPARISON")
            if (response.body == null) {
                throw IllegalStateException(
                    "Attachment response body is empty for \"$attachmentLabel\" (${response.code})"
                )
            }
            val body = response.body
            zip.putNextEntry(ZipEntry(entryPath))
            body.byteStream().use { input ->
                input.copyTo(zip)
            }
            zip.closeEntry()
        }
    }

    private suspend fun writeCachedResourceToTransferZip(
        zip: ZipOutputStream,
        entryPath: String,
        resource: Resource,
        accountKey: String,
    ): Boolean {
        val localResource = database.memoDao().getResourceByRemoteId(resource.remoteId, accountKey)
            ?: return false
        val localFile = localFileForResource(localResource)
            ?: return false
        if (!localFile.exists() || localFile.length() <= 0L) {
            return false
        }
        zip.putNextEntry(ZipEntry(entryPath))
        localFile.inputStream().buffered().use { input ->
            input.copyTo(zip)
        }
        zip.closeEntry()
        return true
    }

    private fun resolveTransferAttachmentFilename(rawFilename: String, attachmentIndex: Int): String {
        val trimmed = rawFilename.trim()
        if (trimmed.isNotEmpty()) {
            return trimmed
        }
        return "attachment-${attachmentIndex + 1}.bin"
    }

    private fun buildTransferAttachmentEntryPath(
        memoIndex: Int,
        attachmentIndex: Int,
        filename: String,
    ): String {
        val memoSegment = (memoIndex + 1).toString().padStart(4, '0')
        val attachmentSegment = (attachmentIndex + 1).toString().padStart(3, '0')
        return "$keerTransferAttachmentsPrefix/memo-$memoSegment/$attachmentSegment-${filename.sanitizeTransferFilename()}"
    }

    private fun String.sanitizeTransferFilename(): String {
        val sanitized = replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
        return sanitized.ifEmpty { "attachment.bin" }
    }

    private fun normalizeUserIdentifier(raw: String): String {
        return raw.trim()
            .substringBefore('|')
            .substringAfterLast('/')
            .trim()
    }

    private fun buildRemoteMemoImportId(host: String, userId: String, remoteMemoId: String): String {
        val raw = listOf(
            host.trim(),
            userId.trim(),
            remoteMemoId.trim(),
        ).joinToString("\u001f")
        return "moememos:$keerTransferImportIdVersion:${sha256(raw)}"
    }

    private fun sha256(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xFF
                append(hex[value ushr 4])
                append(hex[value and 0x0F])
            }
        }
    }

    private fun <T> requireSuccess(response: ApiResponse<T>): T {
        return when (response) {
            is ApiResponse.Success -> response.data
            is ApiResponse.Failure.Error -> {
                val message = response.getErrorMessage().ifBlank { "Request failed" }
                throw IllegalStateException(message)
            }
            is ApiResponse.Failure.Exception -> throw response.throwable
        }
    }

    private fun localFileForResource(resource: ResourceEntity): File? {
        val uri = (resource.localUri ?: resource.uri).toUri()
        if (uri.scheme != "file") {
            return null
        }
        val path = uri.path ?: return null
        return File(path)
    }

    private fun exportFileExtension(resource: ResourceEntity, sourceFile: File): String {
        val filenameExt = resource.filename.substringAfterLast('.', "")
        if (filenameExt.isNotBlank()) {
            return filenameExt.lowercase(Locale.US)
        }
        val sourceExt = sourceFile.extension
        if (sourceExt.isNotBlank()) {
            return sourceExt.lowercase(Locale.US)
        }
        val fromMime = resource.mimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        return fromMime?.lowercase(Locale.US) ?: ""
    }

    private suspend fun purgeAccountData(accountKey: String) {
        val memoDao = database.memoDao()
        memoDao.deleteResourcesByAccount(accountKey)
        memoDao.deleteMemosByAccount(accountKey)
        fileStorage.deleteAccountFiles(accountKey)
    }

    private suspend fun updateAccountFromSyncedUser(accountKey: String, user: User) {
        mutex.withLock {
            context.settingsDataStore.updateData { settings ->
                val index = settings.usersList.indexOfFirst { it.accountKey == accountKey }
                if (index == -1) {
                    return@updateData settings
                }
                val existingUser = settings.usersList[index]
                val current = parseAccountWithSecureToken(existingUser) ?: return@updateData settings
                val updated = current.withUser(user)
                val users = settings.usersList.toMutableList()
                users[index] = updated.toPersistedUserData(existingUser.settings)
                settings.copy(usersList = users)
            }
        }
    }

    fun createMemosV0Client(host: String, accessToken: String?): Pair<OkHttpClient, MemosV0Api> {
        var client = okHttpClient

        if (!accessToken.isNullOrEmpty()) {
            client = client.newBuilder().addNetworkInterceptor { chain ->
                var request = chain.request()
                if (shouldAttachAccessToken(request.url, host)) {
                    request = request.newBuilder().addHeader("Authorization", "Bearer $accessToken")
                        .build()
                }
                chain.proceed(request)
            }.build()
        }

        return client to Retrofit.Builder()
            .baseUrl(host)
            .client(client)
            .addConverterFactory(networkJson.asConverterFactory("application/json".toMediaType()))
            .addCallAdapterFactory(ApiResponseCallAdapterFactory.create())
            .build()
            .create(MemosV0Api::class.java)
    }

    fun createMemosV1Client(host: String, accessToken: String?): Pair<OkHttpClient, MemosV1Api> {
        val client = okHttpClient.newBuilder().apply {
            if (!accessToken.isNullOrBlank()) {
                addNetworkInterceptor { chain ->
                    var request = chain.request()
                    if (shouldAttachAccessToken(request.url, host)) {
                        request = request.newBuilder()
                            .addHeader("Authorization", "Bearer $accessToken")
                            .build()
                    }
                    chain.proceed(request)
                }
            }
        }.build()

        return client to Retrofit.Builder()
            .baseUrl(host)
            .client(client)
            .addConverterFactory(networkJson.asConverterFactory("application/json".toMediaType()))
            .addCallAdapterFactory(ApiResponseCallAdapterFactory.create())
            .build()
            .create(MemosV1Api::class.java)
    }

    suspend fun checkLoginCompatibility(host: String, allowHigherV1Version: Boolean = false): LoginCompatibility {
        val serverVersion = detectAccountCaseAndVersion(host)
        return when (evaluateVersionPolicy(serverVersion)) {
            VersionPolicy.SUPPORTED -> LoginCompatibility.Supported(serverVersion.accountCase)
            VersionPolicy.TOO_LOW -> LoginCompatibility.Unsupported(R.string.memos_supported_versions.string)
            VersionPolicy.V1_HIGHER -> {
                if (allowHigherV1Version) {
                    LoginCompatibility.Supported(serverVersion.accountCase)
                } else {
                    LoginCompatibility.RequiresConfirmation(
                        accountCase = serverVersion.accountCase,
                        version = serverVersion.version,
                        message = R.string.memos_login_version_higher_warning.string,
                    )
                }
            }
        }
    }

    suspend fun checkCurrentAccountSyncCompatibility(
        isAutomatic: Boolean,
        allowHigherV1Version: String? = null,
    ): SyncCompatibility {
        awaitInitialization()
        val account = currentAccount.first() ?: return SyncCompatibility.Allowed
        if (account !is Account.MemosV0 && account !is Account.MemosV1) {
            return SyncCompatibility.Allowed
        }

        val serverVersion = fetchVersionForAccount(account)
            ?: return if (isAutomatic) {
                SyncCompatibility.Blocked(null)
            } else {
                SyncCompatibility.Blocked(R.string.memos_supported_versions.string)
            }
        return when (evaluateVersionPolicy(serverVersion)) {
            VersionPolicy.SUPPORTED -> SyncCompatibility.Allowed
            VersionPolicy.TOO_LOW -> {
                if (isAutomatic) {
                    SyncCompatibility.Blocked(null)
                } else {
                    SyncCompatibility.Blocked(R.string.memos_supported_versions.string)
                }
            }
            VersionPolicy.V1_HIGHER -> {
                val accepted = isUnsupportedSyncVersionAccepted(account.accountKey(), serverVersion.version)
                if (isAutomatic) {
                    return if (accepted) {
                        SyncCompatibility.Allowed
                    } else {
                        SyncCompatibility.Blocked(null)
                    }
                }
                if (allowHigherV1Version == serverVersion.version) {
                    return SyncCompatibility.Allowed
                }
                if (accepted) {
                    return SyncCompatibility.Allowed
                }
                SyncCompatibility.RequiresConfirmation(
                    version = serverVersion.version,
                    message = R.string.memos_sync_version_higher_warning.string,
                )
            }
        }
    }

    suspend fun rememberAcceptedUnsupportedSyncVersion(version: String) {
        awaitInitialization()
        val accountKey = currentAccount.first()?.accountKey() ?: return
        mutex.withLock {
            context.settingsDataStore.updateData { settings ->
                val users = settings.usersList.toMutableList()
                val index = users.indexOfFirst { it.accountKey == accountKey }
                if (index == -1) {
                    return@updateData settings
                }
                val user = users[index]
                val versions = (user.settings.acceptedUnsupportedSyncVersions + version).distinct()
                users[index] = user.copy(
                    settings = user.settings.copy(acceptedUnsupportedSyncVersions = versions)
                )
                settings.copy(usersList = users)
            }
        }
    }

    suspend fun detectAccountCase(host: String): UserData.AccountCase {
        return detectAccountCaseAndVersion(host).accountCase
    }

    suspend fun getRepository(): AbstractMemoRepository {
        awaitInitialization()
        mutex.withLock {
            return repository
        }
    }

    suspend fun getRemoteRepository(): RemoteRepository? {
        awaitInitialization()
        mutex.withLock {
            return remoteRepository
        }
    }

    private suspend fun detectAccountCaseAndVersion(host: String): ServerVersionInfo {
        val memosV0Status = createMemosV0Client(host, null).second.status().getOrNull()
        val memosV0Version = memosV0Status?.profile?.version?.trim().orEmpty()
        if (memosV0Version.isNotEmpty()) {
            return ServerVersionInfo(UserData.AccountCase.MEMOS_V0, memosV0Version)
        }

        val memosV1Profile = createMemosV1Client(host, null).second.getProfile().getOrThrow()
        val memosV1Version = memosV1Profile.version.trim()
        if (memosV1Version.isNotEmpty()) {
            return ServerVersionInfo(UserData.AccountCase.MEMOS_V1, memosV1Version)
        }

        return ServerVersionInfo(UserData.AccountCase.ACCOUNT_NOT_SET, "")
    }

    private suspend fun fetchVersionForAccount(account: Account): ServerVersionInfo? {
        return when (account) {
            is Account.MemosV0 -> {
                val version = createMemosV0Client(account.info.host, account.info.accessToken)
                    .second
                    .status()
                    .getOrNull()
                    ?.profile
                    ?.version
                    ?.trim()
                    .orEmpty()
                if (version.isBlank()) null else ServerVersionInfo(UserData.AccountCase.MEMOS_V0, version)
            }
            is Account.MemosV1 -> {
                val version = createMemosV1Client(account.info.host, account.info.accessToken)
                    .second
                    .getProfile()
                    .getOrNull()
                    ?.version
                    ?.trim()
                    .orEmpty()
                if (version.isBlank()) null else ServerVersionInfo(UserData.AccountCase.MEMOS_V1, version)
            }
            else -> null
        }
    }

    private suspend fun isUnsupportedSyncVersionAccepted(accountKey: String, version: String): Boolean {
        val userData = context.settingsDataStore.data.first()
            .usersList
            .firstOrNull { it.accountKey == accountKey }
            ?: return false
        return userData.settings.acceptedUnsupportedSyncVersions.contains(version)
    }

    private fun parseAccountWithSecureToken(userData: UserData): Account? {
        val account = Account.parseUserData(userData) ?: return null
        val token = secureTokenStorage.getToken(userData.accountKey)
            .orEmpty()
        return when (account) {
            is Account.MemosV0 -> Account.MemosV0(account.info.copy(accessToken = token))
            is Account.MemosV1 -> Account.MemosV1(account.info.copy(accessToken = token))
            is Account.Local -> account
        }
    }

    private fun Account.toPersistedUserData(settings: UserSettings): UserData {
        return when (this) {
            is Account.MemosV0 -> UserData(
                settings = settings,
                accountKey = accountKey(),
                memosV0 = info.copy(accessToken = "")
            )
            is Account.MemosV1 -> UserData(
                settings = settings,
                accountKey = accountKey(),
                memosV1 = info.copy(accessToken = "")
            )
            is Account.Local -> UserData(
                settings = settings,
                accountKey = accountKey(),
                local = info
            )
        }
    }

    private fun persistAccessToken(account: Account) {
        when (account) {
            is Account.MemosV0 -> secureTokenStorage.saveToken(account.accountKey(), account.info.accessToken)
            is Account.MemosV1 -> secureTokenStorage.saveToken(account.accountKey(), account.info.accessToken)
            is Account.Local -> Unit
        }
    }

    private fun shouldAttachAccessToken(requestUrl: HttpUrl, host: String): Boolean {
        val baseUrl = host.toHttpUrlOrNull() ?: return false
        return requestUrl.scheme == baseUrl.scheme &&
            requestUrl.host == baseUrl.host &&
            requestUrl.port == baseUrl.port
    }

    private suspend fun awaitInitialization() {
        initialization.await()
    }

    private fun evaluateVersionPolicy(serverVersion: ServerVersionInfo): VersionPolicy {
        val version = SemVer.parseOrNull(serverVersion.version) ?: return VersionPolicy.TOO_LOW
        return when (serverVersion.accountCase) {
            UserData.AccountCase.MEMOS_V0 -> {
                if (version < MEMOS_V0_MIN_VERSION) VersionPolicy.TOO_LOW else VersionPolicy.SUPPORTED
            }
            UserData.AccountCase.MEMOS_V1 -> {
                when {
                    version < MEMOS_V1_MIN_VERSION -> VersionPolicy.TOO_LOW
                    version > MEMOS_V1_MAX_VERSION -> VersionPolicy.V1_HIGHER
                    else -> VersionPolicy.SUPPORTED
                }
            }
            else -> VersionPolicy.TOO_LOW
        }
    }

    companion object {
        private val MEMOS_V0_MIN_VERSION = SemVer(0, 21, 0)
        private val MEMOS_V1_MIN_VERSION = SemVer(0, 26, 0)
        private val MEMOS_V1_MAX_VERSION = SemVer(0, 26, 2)
        private const val keerTransferManifestEntryName = "manifest.json"
        private const val keerTransferAttachmentsPrefix = "attachments"
        private const val keerTransferImportIdVersion = "v1"
        private const val hex = "0123456789abcdef"
    }
}
