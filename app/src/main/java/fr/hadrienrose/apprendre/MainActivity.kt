package fr.hadrienrose.apprendre

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Date

/**
 * L'app Apprendre = LA PAGE DE PRISME (`chat.hadrien-rose.com/apprendre?app=1`, la même page qu'au téléphone : on ouvre,
 * il y a une carte ; Cartes · ＋ Ajouter · Thèmes) dans une WebView, et rien d'autre que ce qu'un navigateur ne sait pas
 * faire bien : les fichiers et l'appareil photo des champs <input type="file">, la mise à jour, le rappel de 8 h (avec le
 * nombre de cartes à revoir), « Retenir… » et « Partager → Apprendre » qui ouvrent « ＋ Ajouter ».
 * La fenêtre ne va que sur la page Apprendre et le login Cloudflare (code par e-mail) ; la page ne charge que son API et
 * ses polices (Rules) ; tout autre lien s'ouvre dans le navigateur. UN pont JS (1.3, 29/10) : `ApprendreNative.alarm(json)`,
 * par lequel la page pose l'alarme des cartes ratées (Alarm) ; il n'écoute que la page de Prisme et ne rend rien.
 * Le menu de l'app = l'appui long sur son icône (« Retenir… », « Réglages », « Vérifier les mises à jour »).
 */
class MainActivity : Activity() {
    companion object {
        const val START_URL = BuildConfig.SITE + "/apprendre?app=1"
        const val ACTION_SETTINGS = "fr.hadrienrose.apprendre.SETTINGS"
        const val ACTION_CHECK = "fr.hadrienrose.apprendre.CHECK_UPDATE"
        const val ACTION_RETENIR = "fr.hadrienrose.apprendre.RETENIR"
        const val ACTION_RATEES = "fr.hadrienrose.apprendre.RATEES_OPEN"
        const val ACTION_MATIN = "fr.hadrienrose.apprendre.MATIN_OPEN"
        const val REQ_FILES = 21
        const val REQ_NOTIF = 22
        private const val FIELD_FOCUSED_JS = "(function(){var t=document.activeElement;return t&&(t.tagName==='TEXTAREA'||t.tagName==='INPUT')&&t.offsetParent?1:0})()"
        private const val PREF = "apprendre"
        private const val KEY_NOTIF_ASKED = "notif_asked"
        private const val KEY_ALARM_ASKED = "alarm_perm_asked"
    }

    lateinit var web: WebView
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var pageHost: String = ""
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var photo: File? = null
    private var pendingInstall = false
    private var progress: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#101010")) }
        web = WebView(this)
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        fitBars(root)
        setupWeb()
        Reminder.cancel(this)            // 1.4 : le rappel de 8 h est remplacé par l'alarme du matin
        Matin.schedule(this)
        PhotoProvider.purge(this)
        askNotificationsOnce()
        web.loadUrl(addUrlFor(intent) ?: START_URL)
        handleIntent(intent, false)
        Updater.checkLater(this) { info -> main.post { if (!isFinishing && Updater.shouldPrompt(this, info)) showUpdate(info) } }
    }

    /**
     * Android 15 dessine l'app sous les barres du système (targetSdk 35) : la page garde sa place entre la barre d'état
     * et la barre de gestes, et le clavier ne cache jamais le champ (Vide ton sac).
     */
    private fun fitBars(root: FrameLayout) {
        if (Build.VERSION.SDK_INT < 30) return
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.setSystemBarsAppearance(0,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        root.setOnApplyWindowInsetsListener { v, insets ->
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout())
            v.setPadding(i.left, i.top, i.right, i.bottom)
            WindowInsets.CONSUMED
        }
    }

    private fun setupWeb() {
        val s: WebSettings = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.setSupportMultipleWindows(false)          // un lien « nouvel onglet » passe par shouldOverrideUrlLoading
        s.javaScriptCanOpenWindowsAutomatically = false
        s.userAgentString = s.userAgentString + " ApprendreApp/" + BuildConfig.VERSION_NAME
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(web, true)   // le login Cloudflare Access passe par cloudflareaccess.com puis revient
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (Rules.canNavigate(u.scheme, u.host, u.path)) return false
                openOutside(u)
                return true
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url
                val scheme = u.scheme?.lowercase()
                if (scheme != "http" && scheme != "https") return null      // data:, blob: (aperçus de la page)
                if (request.isForMainFrame) {
                    if (!Rules.canNavigate(u.scheme, u.host, u.path)) return blocked()
                    pageHost = u.host ?: ""                                   // avant ses sous-ressources
                    return null
                }
                return if (Rules.canLoad(pageHost, u.scheme, u.host, u.path)) null else blocked()
            }
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                if (url.startsWith("http")) pageHost = Uri.parse(url).host ?: ""
            }
            override fun onPageFinished(view: WebView, url: String) {
                CookieManager.getInstance().flush()
                if (Rules.isSiteHost(Uri.parse(url).host)) keyboardForFirstField(12)
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (request.isForMainFrame && Rules.isSiteHost(request.url.host)) showOffline(error.description?.toString() ?: "")
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                request.deny()   // ni micro ni caméra pour la page : la photo passe par le champ de fichier
            }
            override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                return chooseFiles(cb, params)
            }
        }
        web.setDownloadListener { url, _, _, _, _ -> openOutside(Uri.parse(url)) }
        web.addJavascriptInterface(NativeBridge(), "ApprendreNative")
    }

    /**
     * Le pont de la page (1.3) : `alarm(json)` = l'heure, le nombre et les réglages de l'alarme des cartes ratées ({at: 0} =
     * rien à sonner). 1.4 : `matin(json)` = l'heure, les jours cochés et la sonnerie de l'alarme du matin. Seule la page de Prisme
     * est écoutée ; tout est revérifié (Rules.parseAlarm, Rules.parseMatin) ; rien ne revient à la page.
     */
    inner class NativeBridge {
        @JavascriptInterface
        fun alarm(json: String?) {
            if (!Rules.isSiteHost(pageHost)) return
            val s = Rules.parseAlarm(json, System.currentTimeMillis())
            Alarm.set(applicationContext, s)
            if (s != null) main.post { askAlarmOnce() }
        }

        @JavascriptInterface
        fun matin(json: String?) {
            if (!Rules.isSiteHost(pageHost)) return
            val s = Rules.parseMatin(json) ?: return
            Matin.set(applicationContext, json!!)
            if (s.jours.isNotEmpty()) main.post { askAlarmOnce(); settingsLine?.text = reminderLine() }
        }
    }

    /**
     * La première alarme posée : si Android ne permet pas encore l'heure exacte (« Alarmes et rappels ») ou le plein écran,
     * on le dit une fois et on ouvre le bon réglage ; ensuite, la ligne des réglages de l'app le rappelle.
     */
    private fun askAlarmOnce() {
        if (isFinishing) return
        val p = getSharedPreferences(PREF, MODE_PRIVATE)
        if (p.getBoolean(KEY_ALARM_ASKED, false)) return
        val action = alarmFixAction() ?: return
        p.edit().putBoolean(KEY_ALARM_ASKED, true).apply()
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.rat_perm_title).setMessage(R.string.rat_perm_body)
            .setPositiveButton(R.string.unknown_open) { _, _ -> openAlarmFix(action) }
            .setNegativeButton(R.string.later, null).show()
    }

    /** Le réglage d'Android qui manque à l'alarme, ou null si elle est prête. */
    private fun alarmFixAction(): String? = when {
        !Alarm.exactAllowed(this) && Build.VERSION.SDK_INT >= 31 -> Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM
        !Alarm.fullScreenAllowed(this) && Build.VERSION.SDK_INT >= 34 -> Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT
        else -> null
    }

    private fun openAlarmFix(action: String) {
        try { startActivity(Intent(action, Uri.parse("package:$packageName"))) } catch (_: Exception) { }
    }

    private fun alarmLine(): String = when {
        alarmFixAction() == Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM -> getString(R.string.rat_line_exact)
        alarmFixAction() != null -> getString(R.string.rat_line_full)
        else -> getString(R.string.rat_line_ok)
    }

    /**
     * « Le clavier s'ouvre tout seul sur chaque carte » (choix d'Hadrien, 27/10) : la page met le curseur dans le champ de
     * réponse, mais Android n'ouvre pas le clavier pour un focus sans toucher. Au chargement, l'app regarde (jusqu'à
     * 12 × 250 ms, le temps que la carte arrive) si un champ a le focus, et ouvre alors le clavier elle-même. D'une carte
     * à l'autre, c'est la page qui garde le champ et le toucher d'Hadrien qui le rouvre.
     */
    private fun keyboardForFirstField(tries: Int) {
        main.postDelayed({
            if (isFinishing) return@postDelayed
            web.evaluateJavascript(FIELD_FOCUSED_JS) { r ->
                if (Rules.wantsKeyboard(r)) {
                    web.requestFocus()
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(web, InputMethodManager.SHOW_IMPLICIT)
                } else if (tries > 1) keyboardForFirstField(tries - 1)
            }
        }, 250)
    }

    private fun blocked() = WebResourceResponse("text/plain", "utf-8", 403, "Forbidden",
        mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))

    // ---------- les champs <input type="file"> de la page ----------

    /**
     * « 📷 Photographier une page » (accept d'images + capture) → l'appareil photo tout de suite ; « Choisir des fichiers »
     * → le sélecteur d'Android (fichiers, galerie), l'appareil photo proposé à côté si le champ prend des images. La photo
     * s'écrit dans le cache de l'app (PhotoProvider) ; aucune permission CAMERA : l'app ne la déclare pas, l'appareil
     * photo du téléphone prend la photo pour elle. La page reçoit toujours une réponse (rien = annulé), sinon le champ ne
     * s'ouvrirait plus.
     */
    private fun chooseFiles(cb: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        fileCallback?.onReceiveValue(null)
        fileCallback = cb
        photo = null
        val accept = Rules.acceptList(params.acceptTypes)
        val camera = if (Rules.acceptsImages(accept)) cameraIntent() else null
        val intent = if (Rules.cameraDirect(accept, params.isCaptureEnabled) && camera != null) camera else {
            val pick = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType(Rules.pickerMime(accept))
            if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            Intent.createChooser(pick, getString(R.string.pick_title)).apply {
                if (camera != null) putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(camera))
            }
        }
        return try { startActivityForResult(intent, REQ_FILES); true } catch (_: ActivityNotFoundException) {
            fileCallback = null; photo?.delete(); photo = null; false
        }
    }

    /** L'appareil photo du téléphone, qui écrit dans une photo neuve de l'app (écriture accordée pour elle seule). */
    private fun cameraIntent(): Intent? {
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        val apps = packageManager.queryIntentActivities(i, 0)
        if (apps.isEmpty()) return null
        val f = try { PhotoProvider.newPhoto(this) } catch (_: Exception) { return null }
        val uri = PhotoProvider.uriFor(this, f)
        photo = f
        val grant = Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
        apps.forEach { grantUriPermission(it.activityInfo.packageName, uri, grant) }   // aussi depuis le sélecteur
        return i.putExtra(MediaStore.EXTRA_OUTPUT, uri).addFlags(grant).apply { clipData = ClipData.newRawUri("", uri) }
    }

    private fun filesResult(resultCode: Int, data: Intent?) {
        val cb = fileCallback ?: return
        fileCallback = null
        val p = photo; photo = null
        val chosen = ArrayList<String>()
        if (resultCode == RESULT_OK && data != null) {
            val clip = data.clipData
            if (clip != null) for (k in 0 until clip.itemCount) clip.getItemAt(k).uri?.let { chosen.add(it.toString()) }
            else data.data?.let { chosen.add(it.toString()) }
        }
        val pUri = p?.let { PhotoProvider.uriFor(this, it).toString() }
        val out = if (resultCode == RESULT_OK) Rules.pickResult(chosen.filter { it != pUri }, pUri, p?.length() ?: 0) else emptyList()
        if (p != null && pUri !in out) p.delete()
        if (p != null) revokeUriPermission(PhotoProvider.uriFor(this, p), Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        cb.onReceiveValue(if (out.isEmpty()) null else out.map { Uri.parse(it) }.toTypedArray())
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_FILES) filesResult(resultCode, data) else super.onActivityResult(requestCode, resultCode, data)
    }

    // ---------- le menu (appui long sur l'icône) : réglages et mise à jour ----------

    /**
     * « Retenir… » (appui long sur l'icône) et « Partager → Apprendre » depuis une autre app (27/10, « tout est carte ») : la page
     * s'ouvre sur « ＋ Ajouter », le texte partagé déjà écrit (dans le fragment de l'adresse, jamais envoyé au serveur avant
     * qu'Hadrien ne touche « Claude fait »). Rien d'autre : un fichier partagé n'est pas pris (la page a son 📎).
     */
    private fun addUrlFor(i: Intent?): String? = when (i?.action) {
        ACTION_RETENIR -> Rules.addUrl(START_URL, null)
        ACTION_RATEES -> { Alarm.stop(this); Rules.ratUrl(START_URL) }
        ACTION_MATIN -> { Matin.stop(this); START_URL }
        Intent.ACTION_SEND -> Rules.addUrl(START_URL, Rules.sharedText(i.getStringExtra(Intent.EXTRA_SUBJECT),
            i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()))
        else -> null
    }

    private fun handleIntent(i: Intent?, fresh: Boolean = true) {
        when (i?.action) {
            ACTION_SETTINGS -> main.post { showSettings() }
            ACTION_CHECK -> main.post { checkNow() }
            ACTION_RETENIR, Intent.ACTION_SEND, ACTION_RATEES, ACTION_MATIN -> if (fresh) addUrlFor(i)?.let { u -> main.post { web.loadUrl(u) } }
        }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun notifAllowed(): Boolean =
        Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Une fois, à la première ouverture (Android 13 et plus) ; ensuite seulement depuis les réglages. */
    private fun askNotificationsOnce() {
        if (notifAllowed()) return
        val p = getSharedPreferences(PREF, MODE_PRIVATE)
        if (p.getBoolean(KEY_NOTIF_ASKED, false)) return
        p.edit().putBoolean(KEY_NOTIF_ASKED, true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
    }

    private fun openNotifSettings() {
        try { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }
        catch (_: Exception) { }
    }

    private var settingsLine: TextView? = null
    private var alarmLineView: TextView? = null

    /** L'alarme du matin (1.4) : la prochaine, ou pourquoi elle ne sonnera pas ; ses réglages vivent dans ⚙ de la page. */
    private fun reminderLine(): String {
        val at = Matin.next(this)
        return when {
            at <= 0L -> getString(R.string.set_off)
            !notifAllowed() -> getString(R.string.set_blocked)
            else -> getString(R.string.set_next, DateFormat.format("EEEE d MMMM", Date(at)).toString(), Matin.load(this).heure.replace(":", " h "))
        }
    }

    /** Réglages : l'alarme du matin (réglée dans ⚙ de la page), celle des ratées, « Vérifier les mises à jour », la version. */
    private fun showSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(4)) }
        val line = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); alpha = 0.85f; text = reminderLine(); setPadding(0, dp(8), 0, 0) }
        settingsLine = line
        line.setOnClickListener {
            if (notifAllowed()) return@setOnClickListener
            if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) ||
                !getSharedPreferences(PREF, MODE_PRIVATE).getBoolean(KEY_NOTIF_ASKED, false)) {
                getSharedPreferences(PREF, MODE_PRIVATE).edit().putBoolean(KEY_NOTIF_ASKED, true).apply()
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            } else openNotifSettings()        // refusée pour de bon : seul le réglage d'Android la rend
        }
        val alarm = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); alpha = 0.7f; text = alarmLine(); setPadding(0, dp(14), 0, 0) }
        alarmLineView = alarm
        alarm.setOnClickListener { alarmFixAction()?.let { openAlarmFix(it) } }
        val check = Button(this).apply { text = getString(R.string.check_updates); setOnClickListener { checkNow() } }
        val ver = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); alpha = 0.55f; gravity = Gravity.START; setPadding(0, dp(12), 0, 0)
            text = getString(R.string.set_version, BuildConfig.VERSION_NAME, Build.VERSION.RELEASE)
        }
        box.addView(line); box.addView(alarm); box.addView(check, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) }); box.addView(ver)
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.set_title).setView(box).setPositiveButton(R.string.close, null)
            .setOnDismissListener { settingsLine = null; alarmLineView = null }.show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF) settingsLine?.text = reminderLine()
    }

    /** « Vérifier les mises à jour » : tout de suite, et la réponse est dite (à jour, nouvelle version, ou pourquoi pas). */
    private fun checkNow() {
        Toast.makeText(this, R.string.checking, Toast.LENGTH_SHORT).show()
        Thread {
            val r = Updater.check(this)
            main.post {
                if (isFinishing) return@post
                when {
                    !r.optBoolean("ok") -> Toast.makeText(this, getString(R.string.check_failed, r.optString("error")), Toast.LENGTH_LONG).show()
                    r.optBoolean("available") -> showUpdate(r)
                    else -> Toast.makeText(this, getString(R.string.up_to_date, BuildConfig.VERSION_NAME), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** « Nouvelle version x.y — Mettre à jour » (Plus tard : plus proposée avant demain). */
    private fun showUpdate(info: JSONObject) {
        val latest = info.optJSONObject("latest") ?: return
        Updater.markPrompted(this, info)
        val size = latest.optLong("size")
        val msg = StringBuilder()
        latest.optString("notes").takeIf { it.isNotBlank() }?.let { msg.append(it).append("\n\n") }
        msg.append(getString(R.string.upd_installed, BuildConfig.VERSION_NAME))
        if (size > 0) msg.append(" · ").append(String.format("%.1f Mo", size / 1048576.0))
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(getString(R.string.upd_title, latest.optString("name")))
            .setMessage(msg.toString())
            .setPositiveButton(R.string.upd_go) { _, _ -> startInstall() }
            .setNegativeButton(R.string.later, null)
            .show()
    }

    private fun startInstall() {
        if (!packageManager.canRequestPackageInstalls()) {
            // l'autorisation « sources inconnues » pour Apprendre : le réglage s'ouvre, l'installation reprend au retour
            pendingInstall = true
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(R.string.unknown_title).setMessage(R.string.unknown_body)
                .setPositiveButton(R.string.unknown_open) { _, _ ->
                    try { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) } catch (_: Exception) { }
                }
                .setNegativeButton(R.string.later) { _, _ -> pendingInstall = false }
                .show()
            return
        }
        pendingInstall = false
        val dlg = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.upd_running).setMessage(getString(R.string.upd_download, 0)).setCancelable(false).show()
        progress = dlg
        val r = Updater.install(this) { ev, data ->
            main.post {
                when (ev) {
                    "update-progress" -> {
                        val total = data.optLong("total"); val done = data.optLong("done")
                        dlg.setMessage(if (data.optBoolean("verified")) getString(R.string.upd_verified)
                            else getString(R.string.upd_download, if (total > 0) (done * 100 / total).toInt() else 0))
                    }
                    "update-ready" -> { dlg.dismiss(); Toast.makeText(this, R.string.upd_confirm, Toast.LENGTH_LONG).show() }
                    "update-error" -> {
                        dlg.dismiss()
                        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                            .setTitle(R.string.upd_failed).setMessage(data.optString("error")).setPositiveButton(R.string.close, null).show()
                    }
                }
            }
        }
        if (!r.optBoolean("ok")) {
            dlg.dismiss()
            Toast.makeText(this, getString(R.string.upd_refused, r.optString("error")), Toast.LENGTH_LONG).show()
        }
    }

    // ---------- le reste ----------

    /** Pas de réseau : une page minimale, Réessayer. */
    private fun showOffline(detail: String) {
        val html = """<!doctype html><html lang="fr"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<body style="margin:0;background:#101010;color:#fdfbf0;font:16px system-ui;display:flex;min-height:100vh;align-items:center;justify-content:center;text-align:center">
<div style="padding:24px;max-width:360px"><h2 style="font-weight:600;margin:0 0 10px">${getString(R.string.offline_title)}</h2>
<p style="color:#a8a39a;line-height:1.45">${getString(R.string.offline_body)}</p><p style="color:#6b675f;font-size:12px">${detail.replace("<", "&lt;")}</p>
<a href="$START_URL" style="display:inline-block;margin-top:14px;padding:12px 22px;border-radius:999px;background:#fdfbf0;color:#101010;text-decoration:none;font-weight:600">${getString(R.string.offline_retry)}</a></div></body></html>"""
        web.loadDataWithBaseURL("about:blank", html, "text/html", "utf-8", null)
    }

    private fun openOutside(u: Uri) {
        // un lien s'ouvre ailleurs SANS rien de l'app : ni fichier, ni permission (http(s), mailto, tel seulement)
        val s = u.scheme?.lowercase()
        if (s != "https" && s != "http" && s != "mailto" && s != "tel") return
        try { startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Retour = une couche de la page (elle gère son historique : feuille du panier, séance, onglets) ; au bout, l'app passe derrière
        if (web.canGoBack() && Rules.isSiteHost(pageHost)) web.goBack() else moveTaskToBack(true)
    }

    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent) }

    override fun onResume() {
        super.onResume()
        settingsLine?.text = reminderLine()
        alarmLineView?.text = alarmLine()
        if (pendingInstall && packageManager.canRequestPackageInstalls()) startInstall()
    }

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null); fileCallback = null
        progress?.dismiss()
        web.destroy()
        super.onDestroy()
    }
}
