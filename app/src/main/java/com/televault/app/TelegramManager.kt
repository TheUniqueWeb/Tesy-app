package com.televault.app

import android.content.Context
import android.os.Build
import org.drinkless.td.libcore.telegram.Client
import org.drinkless.td.libcore.telegram.TdApi
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class TelegramManager(
    private val context: Context,
    private val onAuthState: (String) -> Unit
) {
    private var client: Client? = null
    private var apiId: Int = 0
    private var apiHash: String = ""

    private val pendingUploads = ConcurrentHashMap<Long, (Long, Int) -> Unit>()
    private val pendingDownloads = ConcurrentHashMap<Int, (String) -> Unit>()

    fun start(apiId: Int, apiHash: String) {
        this.apiId = apiId
        this.apiHash = apiHash
        if (client != null) return

        client = Client.create(
            { obj -> handleUpdate(obj) },
            null,
            null
        )
        client?.send(TdApi.SetLogVerbosityLevel(1), null)
    }

    private fun handleUpdate(obj: TdApi.Object) {
        when (obj) {
            is TdApi.UpdateAuthorizationState -> {
                when (obj.authorizationState) {
                    is TdApi.AuthorizationStateWaitTdlibParameters -> sendParameters()
                    is TdApi.AuthorizationStateWaitPhoneNumber -> onAuthState("WAIT_PHONE")
                    is TdApi.AuthorizationStateWaitCode -> onAuthState("WAIT_CODE")
                    is TdApi.AuthorizationStateWaitPassword -> onAuthState("WAIT_PASSWORD")
                    is TdApi.AuthorizationStateReady -> onAuthState("READY")
                    is TdApi.AuthorizationStateClosed -> onAuthState("CLOSED")
                }
            }

            is TdApi.UpdateMessageSendSucceeded -> {
                val callback = pendingUploads.remove(obj.oldMessageId)
                if (callback != null) {
                    val msg = obj.message
                    val doc = msg.content as? TdApi.MessageDocument
                    val fileId = doc?.document?.document?.id ?: 0
                    callback(msg.id, fileId)
                }
            }

            is TdApi.UpdateFile -> {
                val f = obj.file
                if (f.local.isDownloadingCompleted && f.local.path.isNotEmpty()) {
                    pendingDownloads.remove(f.id)?.invoke(f.local.path)
                }
            }
        }
    }

    private fun sendParameters() {
        val tdDir = File(context.filesDir, "tdlib")
        val params = TdApi.SetTdlibParameters().apply {
            useTestDc = false
            databaseDirectory = File(tdDir, "db").absolutePath
            filesDirectory = File(tdDir, "files").absolutePath
            databaseEncryptionKey = ByteArray(0)
            useFileDatabase = true
            useChatInfoDatabase = true
            useMessageDatabase = true
            useSecretChats = false
            apiId = this@TelegramManager.apiId
            apiHash = this@TelegramManager.apiHash
            systemLanguageCode = "en"
            deviceModel = Build.MODEL
            systemVersion = Build.VERSION.RELEASE
            applicationVersion = "1.0"
        }
        client?.send(params, null)
    }

    fun sendPhone(phone: String, onError: (String) -> Unit) {
        client?.send(TdApi.SetAuthenticationPhoneNumber(phone, null)) { res ->
            if (res is TdApi.Error) onError(res.message)
        }
    }

    fun sendOtp(code: String, onError: (String) -> Unit) {
        client?.send(TdApi.CheckAuthenticationCode(code)) { res ->
            if (res is TdApi.Error) onError(res.message)
        }
    }

    fun send2FaPassword(password: String, onError: (String) -> Unit) {
        client?.send(TdApi.CheckAuthenticationPassword(password)) { res ->
            if (res is TdApi.Error) onError(res.message)
        }
    }

    fun createPrivateChannel(title: String, onResult: (Long?, String?) -> Unit) {
        val req = TdApi.CreateNewSupergroupChat().apply {
            this.title = "TV_DB_$title"
            this.isForum = false
            this.isChannel = true
            this.description = "TeleVault Private Cloud Database"
        }
        client?.send(req) { res ->
            when (res) {
                is TdApi.Chat -> onResult(res.id, null)
                is TdApi.Error -> onResult(null, res.message)
                else -> onResult(null, "অজানা ত্রুটি")
            }
        }
    }

    fun uploadFile(chatId: Long, file: File, caption: String, onDone: (Long?, Int?, String?) -> Unit) {
        val msgContent = TdApi.InputMessageDocument().apply {
            document = TdApi.InputFileLocal(file.absolutePath)
            this.caption = TdApi.FormattedText(caption, emptyArray())
        }
        val req = TdApi.SendMessage().apply {
            this.chatId = chatId
            this.inputMessageContent = msgContent
        }
        client?.send(req) { res ->
            when (res) {
                is TdApi.Message -> {
                    pendingUploads[res.id] = { finalMsgId, finalFileId ->
                        onDone(finalMsgId, finalFileId, null)
                    }
                }
                is TdApi.Error -> onDone(null, null, res.message)
            }
        }
    }

    fun downloadFile(fileId: Int, onDone: (File?, String?) -> Unit) {
        pendingDownloads[fileId] = { path -> onDone(File(path), null) }
        val req = TdApi.DownloadFile(fileId, 32, 0, 0, true)
        client?.send(req) { res ->
            if (res is TdApi.Error) {
                pendingDownloads.remove(fileId)
                onDone(null, res.message)
            }
        }
    }
}
