package com.televault.app

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var db: VaultDbHelper
    private lateinit var tg: TelegramManager

    private lateinit var statusText: TextView
    private lateinit var apiIdInput: EditText
    private lateinit var apiHashInput: EditText
    private lateinit var phoneInput: EditText
    private lateinit var otpInput: EditText
    private lateinit var passwordInput: EditText

    private lateinit var dbNameInput: EditText
    private lateinit var activeDbText: TextView
    private lateinit var searchInput: EditText
    private lateinit var filesContainer: LinearLayout
    private lateinit var projectsContainer: LinearLayout

    private var selectedProject: ProjectDb? = null

    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) uploadSelectedUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = VaultDbHelper(this)
        tg = TelegramManager(this) { state ->
            runOnUiThread {
                statusText.text = "Telegram Status: $state"
            }
        }

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 48, 36, 48)
        }
        scroll.addView(root)

        fun addTitle(text: String) {
            root.addView(TextView(this).apply {
                this.text = text
                textSize = 18f
                setPadding(0, 28, 0, 12)
            })
        }

        fun makeInput(hint: String): EditText = EditText(this).apply {
            this.hint = hint
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }

        fun makeBtn(label: String, onClick: () -> Unit): Button = Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }

        root.addView(TextView(this).apply {
            text = "🔐 TeleVault Personal Cloud"
            textSize = 24f
        })
        statusText = TextView(this).apply {
            text = "Telegram Status: Disconnected"
            setPadding(0, 8, 0, 16)
        }
        root.addView(statusText)

        addTitle("১. টেলিগ্রাম লগইন (my.telegram.org)")
        apiIdInput = makeInput("API_ID (সংখ্যা)").also { root.addView(it) }
        apiHashInput = makeInput("API_HASH").also { root.addView(it) }
        root.addView(makeBtn("TDLib কানেক্ট করুন") {
            val id = apiIdInput.text.toString().trim().toIntOrNull()
            val hash = apiHashInput.text.toString().trim()
            if (id == null || hash.isEmpty()) {
                toast("সঠিক API_ID ও API_HASH দিন")
            } else {
                tg.start(id, hash)
            }
        })

        phoneInput = makeInput("ফোন নম্বর (+8801XXXXXXXXX)").also { root.addView(it) }
        root.addView(makeBtn("OTP পাঠান") {
            tg.sendPhone(phoneInput.text.toString().trim()) { err -> runOnUiThread { toast(err) } }
        })

        otpInput = makeInput("টেলিগ্রাম OTP কোড").also { root.addView(it) }
        root.addView(makeBtn("OTP যাচাই করুন") {
            tg.sendOtp(otpInput.text.toString().trim()) { err -> runOnUiThread { toast(err) } }
        })

        passwordInput = makeInput("2FA পাসওয়ার্ড (যদি থাকে)").also { root.addView(it) }
        root.addView(makeBtn("2FA আনলক") {
            tg.send2FaPassword(passwordInput.text.toString()) { err -> runOnUiThread { toast(err) } }
        })

        addTitle("২. নতুন চ্যানেল ডেটাবেস ও API Key তৈরি")
        dbNameInput = makeInput("প্রজেক্ট/ওয়েবসাইটের নাম (যেমন: MyShop)").also { root.addView(it) }
        root.addView(makeBtn("+ Create Channel DB & API Key") {
            val name = dbNameInput.text.toString().trim()
            if (name.isEmpty()) return@makeBtn toast("নাম লিখুন")
            tg.createPrivateChannel(name) { chatId, err ->
                runOnUiThread {
                    if (chatId == null) {
                        toast("ত্রুটি: $err")
                    } else {
                        val apiKey = CryptoHelper.generateApiKey()
                        val encKey = CryptoHelper.generateAes256KeyBase64()
                        db.insertProject(name, chatId, apiKey, encKey)
                        dbNameInput.setText("")
                        toast("চ্যানেল তৈরি হয়েছে! ID: $chatId")
                        refreshProjects()
                    }
                }
            }
        })

        projectsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(projectsContainer)

        addTitle("৩. ফাইল এনক্রিপ্ট, আপলোড ও সার্চ")
        activeDbText = TextView(this).apply { text = "কোনো ডেটাবেস সিলেক্ট করা নেই" }
        root.addView(activeDbText)

        root.addView(makeBtn("ফাইল এনক্রিপ্ট করে আপলোড করুন") {
            if (selectedProject == null) return@makeBtn toast("আগে একটি ডেটাবেস সিলেক্ট করুন")
            pickFileLauncher.launch("*/*")
        })

        root.addView(makeBtn("SQLite ডেটাবেস ব্যাকআপ পাঠান") {
            backupSqliteToSelectedChannel()
        })

        searchInput = makeInput("ফাইলের নাম লিখে খুঁজুন...").also { root.addView(it) }
        root.addView(makeBtn("সার্চ / রিফ্রেশ") { refreshFiles() })

        filesContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(filesContainer)

        setContentView(scroll)
        refreshProjects()
    }

    private fun refreshProjects() {
        projectsContainer.removeAllViews()
        val list = db.getAllProjects()
        if (selectedProject == null && list.isNotEmpty()) {
            selectProject(list.first())
        }
        list.forEach { p ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 16, 16, 16)
            }
            row.addView(TextView(this).apply {
                text = "📁 ${p.name} | Channel: ${p.tgChatId}\nAPI Key: ${p.apiKey}"
                textSize = 13f
            })
            val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            btns.addView(Button(this).apply {
                text = "সিলেক্ট"
                setOnClickListener { selectProject(p) }
            })
            btns.addView(Button(this).apply {
                text = "Info কপি"
                setOnClickListener {
                    val info = "PROJECT=${p.name}\nCHANNEL_ID=${p.tgChatId}\nAPI_KEY=${p.apiKey}\nAES_KEY=${p.encKey}"
                    val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("TeleVaultInfo", info))
                    toast("তথ্য কপি হয়েছে!")
                }
            })
            row.addView(btns)
            projectsContainer.addView(row)
        }
    }

    private fun selectProject(p: ProjectDb) {
        selectedProject = p
        activeDbText.text = "সিলেক্টেড DB: ${p.name} (Channel: ${p.tgChatId})"
        refreshFiles()
    }

    private fun uploadSelectedUri(uri: Uri) {
        val proj = selectedProject ?: return
        val fileName = getFileName(uri)
        toast("এনক্রিপ্ট ও আপলোড হচ্ছে: $fileName")

        Thread {
            try {
                val encFile = File(cacheDir, "${System.currentTimeMillis()}_$fileName.vault")
                contentResolver.openInputStream(uri)?.use { input ->
                    CryptoHelper.encryptStreamToFile(input, encFile, proj.encKey)
                }
                val origSize = encFile.length()
                tg.uploadFile(proj.tgChatId, encFile, "TeleVault: $fileName") { msgId, fileId, err ->
                    runOnUiThread {
                        if (msgId != null && fileId != null) {
                            db.insertFile(proj.id, fileName, msgId, fileId, origSize)
                            toast("আপলোড সম্পন্ন: $fileName")
                            refreshFiles()
                        } else {
                            toast("আপলোড ব্যর্থ: $err")
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { toast("ত্রুটি: ${e.message}") }
            }
        }.start()
    }

    private fun backupSqliteToSelectedChannel() {
        val proj = selectedProject ?: return toast("একটি ডেটাবেস সিলেক্ট করুন")
        Thread {
            try {
                val dbFile = db.getDbFile()
                val encBackup = File(cacheDir, "backup_${System.currentTimeMillis()}.db.vault")
                dbFile.inputStream().use { input ->
                    CryptoHelper.encryptStreamToFile(input, encBackup, proj.encKey)
                }
                tg.uploadFile(proj.tgChatId, encBackup, "SQLite Backup") { msgId, _, err ->
                    runOnUiThread {
                        if (msgId != null) toast("SQLite ব্যাকআপ টেলিগ্রামে সেভ হয়েছে!")
                        else toast("ব্যাকআপ ব্যর্থ: $err")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { toast("ত্রুটি: ${e.message}") }
            }
        }.start()
    }

    private fun refreshFiles() {
        filesContainer.removeAllViews()
        val proj = selectedProject ?: return
        val q = searchInput.text.toString().trim()
        val files = db.searchFiles(proj.id, q)
        files.forEach { f ->
            filesContainer.addView(Button(this).apply {
                text = "⬇️ ${f.fileName} (${f.fileSize / 1024} KB) — আনলক করুন"
                setOnClickListener { downloadAndDecrypt(f, proj) }
            })
        }
    }

    private fun downloadAndDecrypt(vf: VaultFile, proj: ProjectDb) {
        toast("ডাউনলোড ও আনলক হচ্ছে...")
        tg.downloadFile(vf.tgFileId) { encFile, err ->
            if (encFile == null) {
                runOnUiThread { toast("ডাউনলোড ব্যর্থ: $err") }
                return@downloadFile
            }
            Thread {
                try {
                    val outDir = File(filesDir, "unlocked").apply { mkdirs() }
                    val outFile = File(outDir, vf.fileName)
                    CryptoHelper.decryptFile(encFile, outFile, proj.encKey)
                    runOnUiThread {
                        toast("আনলক হয়ে সেভ হয়েছে:\n${outFile.absolutePath}")
                    }
                } catch (e: Exception) {
                    runOnUiThread { toast("ডিক্রিপ্ট ত্রুটি: ${e.message}") }
                }
            }.start()
        }
    }

    private fun getFileName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && idx >= 0) return c.getString(idx)
        }
        return "file_${System.currentTimeMillis()}"
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
