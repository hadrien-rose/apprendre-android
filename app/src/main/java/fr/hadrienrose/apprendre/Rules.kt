package fr.hadrienrose.apprendre

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Les règles PURES de l'app (testées sans Android, app/src/test) : où la fenêtre peut aller et ce qu'elle peut charger,
 * l'heure de l'alarme du matin, le fichier confiné d'une photo, ce que demande un champ <input type="file">, les versions.
 */
object Rules {
    /** Le site de Prisme : la fenêtre n'y va que pour la page Apprendre. */
    const val HOST = "chat.hadrien-rose.com"
    /** Le login Cloudflare Access (code par e-mail) : autorisé dans la fenêtre. */
    private val LOGIN_HOSTS = listOf("cloudflareaccess.com")
    /** Où la fenêtre peut ALLER sur notre site : la page, et le retour du login Access (/cdn-cgi/access/authorized). */
    private val NAV_EXACT = setOf("/apprendre")
    private val NAV_PREFIXES = listOf("/apprendre/", "/cdn-cgi/access/")
    /** Ce que la page peut CHARGER sur notre site : elle-même, son API, ses polices, les scripts de Cloudflare. */
    private val LOAD_EXACT = setOf("/apprendre", "/api/apprendre")
    private val LOAD_PREFIXES = listOf("/apprendre/", "/api/apprendre/", "/fonts/", "/cdn-cgi/")
    /** Les adresses des APK publiées (l'Updater n'en télécharge aucune autre). */
    const val RELEASES = "https://github.com/hadrien-rose/apprendre-android/releases/download/"

    /** Les heures se comptent à Paris (où que soit le téléphone). */
    val ZONE: ZoneId = ZoneId.of("Europe/Paris")
    const val DAY_MS = 24 * 3600 * 1000L

    private val PHOTO_RE = Regex("^photo-[0-9]{8}-[0-9]{6}(-[0-9]{1,3})?\\.jpg$")
    private val IMAGE_EXT = setOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".heic", ".heif")

    fun isSiteHost(host: String?): Boolean = host.equals(HOST, ignoreCase = true)

    /** Le résultat de evaluateJavascript (« 1 », « 0 », « null », une chaîne entre guillemets) : un champ a-t-il le focus ? */
    fun wantsKeyboard(jsResult: String?): Boolean = jsResult?.trim()?.trim('"') == "1"

    fun isLoginHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.lowercase()
        return LOGIN_HOSTS.any { h == it || h.endsWith(".$it") }
    }

    /** Un chemin propre : absolu, sans « . » ni « .. », sans point, barre ou antislash encodés, sans « // ». */
    fun isCleanPath(path: String?): Boolean {
        if (path == null || !path.startsWith("/")) return false
        val low = path.lowercase()
        if ('\\' in path || "//" in path || "%2e" in low || "%2f" in low || "%5c" in low) return false
        return path.split('/').none { it == "." || it == ".." }
    }

    private fun under(path: String, exact: Set<String>, prefixes: List<String>) = path in exact || prefixes.any { path.startsWith(it) }

    /** La fenêtre peut-elle aller là ? Sinon le lien s'ouvre dans le navigateur. */
    fun canNavigate(scheme: String?, host: String?, path: String?): Boolean {
        if (!scheme.equals("https", ignoreCase = true)) return false
        if (isLoginHost(host)) return true
        if (!isSiteHost(host)) return false
        val p = if (path.isNullOrEmpty()) "/" else path
        return isCleanPath(p) && under(p, NAV_EXACT, NAV_PREFIXES)
    }

    /**
     * La page affichée (`pageHost`) peut-elle charger cette adresse (http ou https) ? Sur notre site, ses seuls chemins ;
     * le login Cloudflare charge ce qu'il veut ; notre page ne charge rien d'ailleurs.
     */
    fun canLoad(pageHost: String?, scheme: String?, host: String?, path: String?): Boolean {
        if (!scheme.equals("https", ignoreCase = true)) return false
        if (isSiteHost(host)) {
            val p = if (path.isNullOrEmpty()) "/" else path
            return isCleanPath(p) && under(p, LOAD_EXACT, LOAD_PREFIXES)
        }
        if (isLoginHost(host)) return true
        return isLoginHost(pageHost)
    }

    // ---------- les fichiers : <input type="file" accept=… capture=…> ----------

    /** Les types d'un attribut accept (WebView les rend en tableau, parfois en une chaîne à virgules). */
    fun acceptList(types: Array<String>?): List<String> =
        (types ?: emptyArray()).flatMap { it.split(',') }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    private fun isImageType(t: String) = t.startsWith("image/") || t in IMAGE_EXT

    /** Le champ n'accepte que des images (« 📷 Photographier une page », un accept d'images seulement). */
    fun imagesOnly(accept: List<String>): Boolean = accept.isNotEmpty() && accept.all { isImageType(it) }

    /** Le champ accepte au moins une image : l'appareil photo est proposé à côté des fichiers. Rien de précisé = tout. */
    fun acceptsImages(accept: List<String>): Boolean = accept.isEmpty() || accept.any { isImageType(it) }

    /** capture="environment" sur un champ d'images = l'appareil photo tout de suite, sans sélecteur. */
    fun cameraDirect(accept: List<String>, capture: Boolean): Boolean = capture && imagesOnly(accept)

    /**
     * Le type demandé au sélecteur d'Android : les images pour un champ d'images, sinon tout — les
     * sélecteurs filtrent mal un .md ou un .doc par type, et c'est le SERVEUR qui refuse un format (`sniff_ok`).
     */
    fun pickerMime(accept: List<String>): String = if (imagesOnly(accept)) "image/*" else "*/*"

    /** Ce que la page reçoit : les fichiers choisis, sinon la photo si l'appareil l'a écrite (non vide), sinon rien. */
    fun pickResult(chosen: List<String>, photo: String?, photoBytes: Long): List<String> = when {
        chosen.isNotEmpty() -> chosen
        photo != null && photoBytes > 0 -> listOf(photo)
        else -> emptyList()
    }

    // ---------- la photo prise pour la page ----------

    /** « photo-AAAAMMJJ-HHMMSS.jpg » à l'heure de Paris (le nom que la page et Prisme verront), `k` pour départager. */
    fun photoName(now: Long, k: Int = 0): String {
        val s = Instant.ofEpochMilli(now).atZone(ZONE).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        return if (k > 0) "photo-$s-$k.jpg" else "photo-$s.jpg"
    }

    /** Le fichier d'une photo, CONFINÉ au dossier des photos (jamais un chemin libre), ou null. */
    fun photoFile(root: File, name: String?): File? {
        if (name == null || !PHOTO_RE.matches(name)) return null
        val f = File(root, name)
        val rootC = root.canonicalFile.path + File.separator
        return if (f.canonicalFile.path.startsWith(rootC)) f else null
    }

    /** Le jour de Paris d'un instant (« 2026-10-27 »). */
    fun parisDay(now: Long): String = Instant.ofEpochMilli(now).atZone(ZONE).toLocalDate().toString()

    // ---------- les versions ----------

    /** Vrai si la version publiée (code) est plus récente que l'installée. */
    fun isNewer(latestCode: Int, installedCode: Int): Boolean = latestCode > installedCode

    /**
     * La fenêtre « Nouvelle version » à l'ouverture : une version plus récente, pas encore proposée, ou proposée il y a
     * plus d'un jour (« Plus tard » ne revient pas à chaque ouverture).
     */
    fun shouldPrompt(latestCode: Int, installedCode: Int, promptedCode: Int, promptedAt: Long, now: Long): Boolean =
        isNewer(latestCode, installedCode) && (promptedCode != latestCode || now - promptedAt >= DAY_MS)

    // ---------- « tout est carte » (27/10) : retenir d'ailleurs ----------

    const val SHARE_MAX = 2000

    /**
     * L'adresse de « ＋ Ajouter » dans la page, avec un texte à retenir (Partager depuis une autre app) : le texte voyage dans le
     * fragment (#add&text=…), jamais au serveur ; coupé à SHARE_MAX caractères, encodé (espaces en %20, rien d'interprétable).
     */
    fun addUrl(start: String, text: String?): String {
        val base = start.substringBefore('#') + "#add"
        val t = (text ?: "").replace("\r\n", "\n").trim().take(SHARE_MAX)
        return if (t.isEmpty()) base else base + "&text=" + java.net.URLEncoder.encode(t, "UTF-8").replace("+", "%20")
    }

    /** Ce qu'on partage : le texte, précédé du sujet s'il en apporte (le titre d'une page partagée depuis le navigateur). */
    fun sharedText(subject: String?, text: String?): String {
        val s = (subject ?: "").trim(); val t = (text ?: "").trim()
        return if (s.isNotEmpty() && t.isNotEmpty() && !t.contains(s)) "$s — $t" else t.ifEmpty { s }
    }

    // ---------- la carte ratée (1.3, 29/10) : l'alarme que la page demande par ApprendreNative.alarm ----------

    /** Les durées de sonnerie que propose ⚙ (secondes) : 3 s · 5 s · 10 s · 1 min · 5 min ; 1 min par défaut. */
    val ALARM_DUREES = setOf(3, 5, 10, 60, 300)
    /** Une alarme se pose au plus 2 jours devant (au-delà, la page se trompe) ; une heure passée de plus d'une minute est refusée. */
    const val ALARM_AHEAD_MAX = 2 * DAY_MS
    const val SNOOZE_MS = 5 * 60 * 1000L

    data class AlarmSpec(val at: Long, val n: Int, val mode: String, val son: Boolean, val vibreur: Boolean, val duree: Int)

    /**
     * Ce que la page envoie ({at, n, mode, son, vibreur, duree}) → l'alarme à poser ; null = rien à sonner (at 0, une heure
     * passée, trop loin, un JSON illisible) : l'alarme posée est alors annulée. Une valeur inconnue prend le défaut (une
     * vraie alarme, son, vibreur, 1 min) ; le nombre est borné (1 à 999).
     */
    fun parseAlarm(json: String?, now: Long): AlarmSpec? {
        val o = try { org.json.JSONObject(json ?: "") } catch (_: Exception) { return null }
        val at = o.optLong("at", 0L)
        if (at <= 0L || at < now - 60_000L || at > now + ALARM_AHEAD_MAX) return null
        val duree = o.optInt("duree", 60).let { if (it in ALARM_DUREES) it else 60 }
        return AlarmSpec(at, o.optInt("n", 1).coerceIn(1, 999), if (o.optString("mode") == "notif") "notif" else "alarme",
            o.optBoolean("son", true), o.optBoolean("vibreur", true), duree)
    }

    /** Un canal de notification par réglage (Android fige le son et le vibreur d'un canal une fois créé). */
    fun alarmChannel(mode: String, son: Boolean, vibreur: Boolean): String =
        "ratees_" + (if (mode == "notif") "notif" else "alarme") + "_" + (if (son) "s" else "0") + (if (vibreur) "v" else "0")

    /** Le titre de l'alarme : le nombre seul, rien d'une carte. */
    fun alarmText(n: Int): String = if (n > 1) "$n cartes à revoir" else "1 carte à revoir"

    /** Toucher l'alarme = les cartes ratées, directement (#r=rat). */
    fun ratUrl(start: String): String = start.substringBefore('#') + "#r=rat"

    // ---------- l'alarme du matin (1.4, chantier 4 du 30/10, maquette apprendre-cartes-du-jour) ----------

    /** Ce que la page envoie par ApprendreNative.matin : l'heure, les jours cochés (0 = lundi), le début de la journée, la sonnerie. */
    data class MatinSpec(val heure: String, val jours: Set<Int>, val debut: String, val mode: String, val son: Boolean, val vibreur: Boolean,
                         val duree: Int)

    /** Les réglages par défaut, avant que la page ait parlé (son choix : 8 h 50, du lundi au vendredi, une vraie alarme). */
    val MATIN_DEFAULT = MatinSpec("08:50", setOf(0, 1, 2, 3, 4), "06:00", "alarme", true, true, 60)
    private val HHMM = Regex("^([01]\\d|2[0-3]):([0-5]\\d)$")

    /** Le JSON de la page → les réglages ; null si l'heure ou les jours sont faux (rien n'est changé). Aucun jour = pas d'alarme. */
    fun parseMatin(json: String?): MatinSpec? {
        val o = try { org.json.JSONObject(json ?: "") } catch (_: Exception) { return null }
        val heure = o.optString("heure"); val debut = o.optString("debut", "06:00")
        if (!HHMM.matches(heure) || !HHMM.matches(debut)) return null
        val a = o.optJSONArray("jours") ?: return null
        val jours = mutableSetOf<Int>()
        for (i in 0 until a.length()) { val d = a.optInt(i, -1); if (d !in 0..6) return null; jours.add(d) }
        val duree = o.optInt("duree", 60).let { if (it in ALARM_DUREES) it else 60 }
        return MatinSpec(heure, jours, debut, if (o.optString("mode") == "notif") "notif" else "alarme", o.optBoolean("son", true),
            o.optBoolean("vibreur", true), duree)
    }

    /** Le prochain matin coché, STRICTEMENT après `now`, à l'heure dite (Paris) ; -1 si aucun jour n'est coché. */
    fun nextMatinAt(now: Long, heure: String, jours: Set<Int>): Long {
        if (jours.isEmpty() || !HHMM.matches(heure)) return -1L
        val (h, m) = heure.split(":").map { it.toInt() }
        val z = Instant.ofEpochMilli(now).atZone(ZONE)
        for (k in 0..7) {
            val d = z.toLocalDate().plusDays(k.toLong())
            if ((d.dayOfWeek.value - 1) !in jours) continue
            val t = d.atTime(h, m).atZone(ZONE).toInstant().toEpochMilli()
            if (t > now) return t
        }
        return -1L
    }

    /**
     * La réponse de /api/apprendre/matin → (sonner, texte) ; null si illisible. Prisme muet ou illisible : l'alarme SONNE quand
     * même (mieux vaut une alarme de trop qu'une révision manquée), avec un texte sans nombre.
     */
    fun parseMatinAnswer(body: String?): Pair<Boolean, String>? {
        val o = try { org.json.JSONObject(body ?: "") } catch (_: Exception) { return null }
        if (!o.has("sonner")) return null
        val t = o.optString("texte").trim().take(80).ifEmpty { MATIN_TEXT }
        return Pair(o.optBoolean("sonner", true), t)
    }

    const val MATIN_TEXT = "Tes cartes du matin"

    /** Une APK publiée par ce dépôt, en HTTPS, et rien d'autre. */
    fun isReleaseApk(url: String?): Boolean =
        url != null && url.startsWith(RELEASES) && url.endsWith(".apk") && isCleanPath(url.removePrefix("https://github.com"))
}
