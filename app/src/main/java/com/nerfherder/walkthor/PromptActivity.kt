package com.nerfherder.walkthor

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * An invisible, momentary screen that asks Android for something on the drawer's behalf (a file
 * picker, screen capture, a permission), since only an app screen can. It opens, shows Android's
 * prompt, hands the answer back to the drawer, and closes.
 */
class PromptActivity : Activity() {
    private var id = -1
    private var answered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        id = intent.getIntExtra(EXTRA_ID, -1)
        if (savedInstanceState != null) return // already asked; waiting for the answer
        @Suppress("DEPRECATION")
        val ask: Intent? = intent.getParcelableExtra(EXTRA_ASK)
        if (ask == null || !callbacks.containsKey(id)) { finish(); return }
        if (ask.action == ActivityResultContracts.RequestMultiplePermissions.ACTION_REQUEST_PERMISSIONS) {
            val perms = ask.getStringArrayExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSIONS) ?: emptyArray()
            requestPermissions(perms, REQUEST)
        } else {
            @Suppress("DEPRECATION")
            runCatching { startActivityForResult(ask, REQUEST) }.onFailure { answer(RESULT_CANCELED, null) }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        answer(resultCode, data)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val data = Intent()
            .putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSIONS, permissions)
            .putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSION_GRANT_RESULTS, grantResults)
        answer(RESULT_OK, data)
    }

    /** Hands the answer back while this screen is still up (screen capture must start while it is). */
    private fun answer(code: Int, data: Intent?) {
        if (answered) return
        answered = true
        runCatching { callbacks.remove(id)?.invoke(code, data) }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        // Closed without an answer (say, Android recreated it and nobody's waiting): give up cleanly.
        if (isFinishing && !answered) runCatching { callbacks.remove(id)?.invoke(RESULT_CANCELED, null) }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ID = "walkthor.prompt.id"
        private const val EXTRA_ASK = "walkthor.prompt.ask"
        private const val REQUEST = 7
        private val next = AtomicInteger(1)
        private val callbacks = ConcurrentHashMap<Int, (Int, Intent?) -> Unit>()

        /** Shows [ask] (an intent for Android's prompt) on [displayId], then calls [onAnswer]. */
        fun ask(context: Context, displayId: Int?, ask: Intent, onAnswer: (Int, Intent?) -> Unit) {
            val id = next.getAndIncrement()
            callbacks[id] = onAnswer
            val intent = Intent(context, PromptActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_ASK, ask)
            val options = displayId?.let { ActivityOptions.makeBasic().setLaunchDisplayId(it).toBundle() }
            runCatching { context.startActivity(intent, options) }.onFailure {
                callbacks.remove(id)
                onAnswer(RESULT_CANCELED, null)
            }
        }
    }
}
