package shop.terramoor.github.io.settings

import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.SystemClock
import shop.terramoor.github.io.systemui.NativeContract

internal object CapsuleSettingsUpdate {
    private const val SNAPSHOT = "snapshot"
    private const val COMMITTED_AT = "committed_at"
    private const val LIVE_CMD = "live_cmd"
    private const val LIVE_OFFSET_MS = "live_offset_ms"
    const val CMD_REFRESH_LYRICS = "refresh_lyrics"
    const val CMD_SET_OFFSET = "set_offset"
    const val CMD_SEEK_TO = "seek_to"

    private fun action(packageName: String) = "$packageName.CAPSULE_SETTINGS_COMMITTED"
    private fun liveAction(packageName: String) = "$packageName.CAPSULE_LIVE_ACTION"
    private fun permission(packageName: String) = "$packageName.permission.CAPSULE_SETTINGS"

    fun publish(context: Context, settings: CapsuleSettings, targetPackage: String = NativeContract.SYSTEM_UI) {
        val intent = Intent(action(context.packageName)).setPackage(targetPackage)
            .putExtra(COMMITTED_AT, SystemClock.elapsedRealtimeNanos())
            .putExtra(SNAPSHOT, CapsuleBackup.encode(settings))
        context.sendBroadcast(intent, null, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
    }

    fun publishLiveCommand(
        context: Context,
        command: String,
        offsetMs: Long = 0L,
        targetPackage: String = NativeContract.SYSTEM_UI,
    ) {
        val intent = Intent(liveAction(context.packageName)).setPackage(targetPackage)
            .putExtra(LIVE_CMD, command)
            .putExtra(LIVE_OFFSET_MS, offsetMs)
        context.sendBroadcast(intent, null, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
    }

    fun register(
        context: Context, modulePackage: String, handler: Handler,
        apply: (CapsuleSettings) -> Unit,
        onLiveCommand: ((command: String, offsetMs: Long) -> Unit)? = null,
    ): BroadcastReceiver {
        val moduleUid = context.packageManager.getPackageUid(modulePackage, 0)
        val updateAction = action(modulePackage)
        val cmdAction = liveAction(modulePackage)
        val receiver = object : BroadcastReceiver() {
            private var lastCommit = 0L

            override fun onReceive(context: Context, intent: Intent) {
                if (sentFromUid != moduleUid || sentFromPackage != modulePackage) return
                if (intent.action == cmdAction) {
                    val cmd = intent.getStringExtra(LIVE_CMD) ?: return
                    val offsetMs = intent.getLongExtra(LIVE_OFFSET_MS, 0L)
                    onLiveCommand?.invoke(cmd, offsetMs)
                    return
                }
                if (intent.action != updateAction) return
                val committedAt = intent.getLongExtra(COMMITTED_AT, 0L)
                if (committedAt <= lastCommit) return
                val snapshot = intent.getStringExtra(SNAPSHOT) ?: return
                val settings = runCatching { CapsuleBackup.decode(snapshot.byteInputStream()) }.getOrNull() ?: return
                lastCommit = committedAt
                apply(settings)
            }
        }
        val filter = IntentFilter(updateAction).apply { addAction(cmdAction) }
        context.registerReceiver(receiver, filter, permission(modulePackage), handler, Context.RECEIVER_EXPORTED)
        return receiver
    }
}
