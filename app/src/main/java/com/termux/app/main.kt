package com.termux.app

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import com.termux.R
import com.termux.app.service.LocalBinder
import com.termux.terminal.TerminalSession
import com.termux.terminal.TermuxTerminalSessionActivityClient
import com.termux.utils.data.EXTRA_NORMAL_BACKGROUND
import com.termux.utils.file.setupStorageSymlinks
import com.termux.utils.ui.NavWindow
import com.termux.utils.ui.blur
import com.termux.view.Con
import java.io.File

/**
 * A terminal emulator activity.
 *
 *
 * See
 *
 *  * [..[*  * https://code.google.com/p/android/iss](.</a></li>
  )ues/detail?id=6426](http://www.mongrel-phones.com.au/default/how_to_make_a_local_service_and_bind_to_it_in_android)
 *
 * about memory leaks.
 */
class main : Activity(), ServiceConnection {
    /**
     * The [Con] shown in  [main] that displays the terminal.
     */
    lateinit var con: Con
    lateinit var blur: blur

    /**
     * The connection to the [mService]. Requested in [.onCreate] with a call to
     * [.bindService], and obtained and stored in
     * [.onServiceConnected].
     */
    lateinit var mService: service
        private set

    /**
     * The {link TermuxTerminalSessionClientBase} interface implementation to allow for communication between
     * [TerminalSession] and [main].
     */
    var termuxTerminalSessionClientBase: TermuxTerminalSessionActivityClient =
        TermuxTerminalSessionActivityClient(this)
        private set

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val serviceIntent = Intent(this, service::class.java)
        startService(serviceIntent)
        this.bindService(serviceIntent, this, 0)
        setupStorageSymlinks(this)
    }

    private fun setWallpaper() {
        if (File(EXTRA_NORMAL_BACKGROUND).exists()) this.window.decorView.background =
            Drawable.createFromPath(EXTRA_NORMAL_BACKGROUND)
    }

    public override fun onDestroy() {
        super.onDestroy()
        unbindService(this)
    }

    /**
     * Part of the [ServiceConnection] interface. The service is bound with
     * [.bindService] in [.onCreate] which will cause a call to this
     * callback method.
     */
    override fun onServiceConnected(componentName: ComponentName, service: IBinder) {
        this.mService = (service as LocalBinder).service
        this.mService.setTermuxTermuxTerminalSessionClientBase(termuxTerminalSessionClientBase)
        this.setContentView(R.layout.activity_termux)
        setTermuxTerminalViewAndClients()
        if (this.mService.isTerminalSessionsEmpty) {
            val session = this.mService.createTerminalSession(false)
            this.mService.TerminalSessions.add(session)
            con.mEmulator = session.emulator
        }
        termuxTerminalSessionClientBase.onStart()
        con.currentSession.write(intent.getStringExtra("cmd"))
        registerForContextMenu(con)
        this.setWallpaper()
        intent = null
    }

    override fun onServiceDisconnected(name: ComponentName) {
        // Respect being stopped from the {@link service} notification action.
        finishActivityIfNotFinishing()
    }

    private fun setTermuxTerminalViewAndClients() {
        con = findViewById(R.id.terminal_view)
        blur = findViewById(R.id.background)
        con.requestFocus()
        NavWindow(this).show()
    }

    fun finishActivityIfNotFinishing() {
        // prevent duplicate calls to finish() if called from multiple places
        if (!isFinishing) {
            finish()
        }
    }

    /**
     * Show a toast and dismiss the last one if still visible.
     */
    fun showToast(text: String, longDuration: Boolean) {
        Toast.makeText(this, text, if (longDuration) Toast.LENGTH_LONG else Toast.LENGTH_SHORT)
            .show()
    }

}