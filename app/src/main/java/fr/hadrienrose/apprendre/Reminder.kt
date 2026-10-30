package fr.hadrienrose.apprendre

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Le rappel de 8 h (1.0 à 1.3, une simple notification, sans trace : le 30/10, la révision du matin n'a pas eu lieu) est RETIRÉ
 * en 1.4 : l'alarme du matin le remplace (Matin.kt, réglée dans ⚙ de la page). Reste de quoi l'annuler une fois chez qui l'avait
 * posé (la mise à jour de l'app, le redémarrage), et un récepteur qui ne fait plus rien.
 */
object Reminder {
    private const val ACTION_FIRE = "fr.hadrienrose.apprendre.REMIND"
    private const val CHANNEL = "rappel"

    fun cancel(ctx: Context) {
        val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ctx, ReminderReceiver::class.java).setAction(ACTION_FIRE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        ctx.getSystemService(AlarmManager::class.java)?.cancel(pi)
        try { ctx.getSystemService(NotificationManager::class.java)?.deleteNotificationChannel(CHANNEL) } catch (_: Exception) { }
    }
}

/** Le récepteur de l'ancien rappel : une alarme posée par la 1.3 qui sonnerait encore n'affiche rien, et s'annule. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) { Reminder.cancel(ctx.applicationContext) }
}

/** Le redémarrage, l'heure ou le fuseau changés, l'app mise à jour (diffusions protégées du système) : les alarmes sont reposées. */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> { Reminder.cancel(ctx); Alarm.schedule(ctx); Matin.schedule(ctx) }
        }
    }
}
