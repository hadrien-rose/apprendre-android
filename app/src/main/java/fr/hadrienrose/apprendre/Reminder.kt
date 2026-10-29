package fr.hadrienrose.apprendre

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Le rappel du jour : une notification locale à 8 h, heure de Paris (Rules.nextReminderAt), sans rien d'un dossier : le
 * nombre de cartes à revoir (27/10), rien de plus. AlarmManager, à quelques minutes près (setAndAllowWhileIdle : traverse la veille sans la
 * permission des alarmes exactes) ; replanifié à chaque ouverture, au redémarrage du téléphone, au changement d'heure
 * et après une mise à jour de l'app. Rien ne part du téléphone : seul un nombre est demandé à Prisme.
 */
object Reminder {
    private const val PREF = "apprendre"
    private const val KEY_ON = "reminder_on"
    private const val KEY_LAST_DAY = "reminder_last_day"
    const val CHANNEL = "rappel"
    const val ACTION_FIRE = "fr.hadrienrose.apprendre.REMIND"
    private const val NOTIF_ID = 8

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** Allumé par défaut (le choix d'Hadrien : un rappel par jour à 8 h). */
    fun isOn(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ON, true)

    fun setOn(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ON, on).apply()
        schedule(ctx)
    }

    private fun firePending(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(ctx, 0, Intent(ctx, ReminderReceiver::class.java).setAction(ACTION_FIRE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** (Re)pose l'alarme du prochain 8 h, ou l'annule si le rappel est éteint. Rend l'instant posé, ou 0. */
    fun schedule(ctx: Context): Long {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return 0
        val pi = firePending(ctx)
        am.cancel(pi)
        if (!isOn(ctx)) return 0
        val at = Rules.nextReminderAt(System.currentTimeMillis())
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        return at
    }

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) != null) return
        val ch = NotificationChannel(CHANNEL, ctx.getString(R.string.channel_name), NotificationManager.IMPORTANCE_DEFAULT)
        ch.description = ctx.getString(R.string.channel_desc)
        nm.createNotificationChannel(ch)
    }

    /**
     * L'alarme a sonné : la notification (une par jour au plus, jamais avant 8 h), puis l'alarme du lendemain. Depuis le 27/10
     * (« tout est carte »), elle dit le nombre de cartes à revoir : un seul nombre demandé à Prisme (/api/apprendre/review/count,
     * avec le login de l'app) ; zéro = pas de rappel ; Prisme muet (hors ligne, login expiré) = le texte d'avant.
     */
    fun fire(ctx: Context, due: Int? = null) {
        val now = System.currentTimeMillis()
        val p = prefs(ctx)
        if (isOn(ctx) && Rules.shouldNotify(p.getString(KEY_LAST_DAY, null), now)) {
            val text = Rules.remindText(due, ctx.getString(R.string.remind_text))
            p.edit().putString(KEY_LAST_DAY, Rules.parisDay(now)).apply()
            if (text != null) show(ctx, text)
        }
        schedule(ctx)
    }

    /** Le nombre de cartes à revoir, demandé à Prisme avec les cookies de l'app (le login Cloudflare Access), ou null. */
    fun fetchDue(): Int? = try {
        val url = BuildConfig.SITE + "/api/apprendre/review/count"
        val c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 10_000; instanceFollowRedirects = false
            android.webkit.CookieManager.getInstance().getCookie(url)?.let { setRequestProperty("Cookie", it) }
            setRequestProperty("Accept", "application/json")
        }
        try { if (c.responseCode == 200) Rules.parseDue(c.inputStream.bufferedReader().use { it.readText() }) else null } finally { c.disconnect() }
    } catch (_: Throwable) { null }

    private fun show(ctx: Context, text: String) {
        ensureChannel(ctx)
        val open = PendingIntent.getActivity(ctx, 1,
            Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(ctx.getString(R.string.remind_title))
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PUBLIC)   // le texte ne dit rien de privé : lisible écran verrouillé
            .build()
        try { ctx.getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, n) } catch (_: SecurityException) { }
    }
}

/** L'alarme de 8 h (la nôtre seulement : non exporté, PendingIntent de l'app). Le nombre se demande hors du fil principal. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Reminder.ACTION_FIRE) return
        val pending = goAsync()
        Thread {
            try { Reminder.fire(ctx.applicationContext, if (Reminder.isOn(ctx)) Reminder.fetchDue() else null) } finally { pending.finish() }
        }.start()
    }
}

/** Le redémarrage, l'heure ou le fuseau changés, l'app mise à jour (diffusions protégées du système) : l'alarme est reposée. */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> { Reminder.schedule(ctx); Alarm.schedule(ctx) }
        }
    }
}
