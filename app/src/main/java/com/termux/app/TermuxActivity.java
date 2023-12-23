package com.termux.app;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;
import com.termux.app.terminal.TermuxTerminalSessionActivityClient;
import com.termux.app.terminal.TermuxTerminalViewClient;
import com.termux.shared.file.FileUtils;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

/**
 * A terminal emulator activity.
 * <p/>
 * See
 * <ul>
 * <li><a href="http://www.mongrel-phones.com.au/default/how_to_make_a_local_service_and_bind_to_it_in_android">..<a href=".</a></li>
 * ">* <li>https://code.google.com/p/android/iss</a>ues/detail?id=6426</li>
 * </ul>
 * about memory leaks.
 */
public final class TermuxActivity extends AppCompatActivity implements ServiceConnection {

    private static final int CONTEXT_MENU_SELECT_URL_ID = 0;
    private static final int CONTEXT_MENU_SHARE_TRANSCRIPT_ID = 1;
    private static final int CONTEXT_MENU_RESET_TERMINAL_ID = 3;
    private static final int CONTEXT_MENU_KILL_PROCESS_ID = 4;
    private static final int CONTEXT_MENU_REMOVE_BACKGROUND_IMAGE_ID = 13;
    private static final int CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON = 6;
    //private final BroadcastReceiver mTermuxActivityBroadcastReceiver = new TermuxActivityBroadcastReceiver();
    /**
     * The connection to the {@link TermuxService}. Requested in {@link #onCreate(Bundle)} with a call to
     * {@link #bindService(Intent, ServiceConnection, int)}, and obtained and stored in
     * {@link #onServiceConnected(ComponentName, IBinder)}.
     */
    TermuxService mTermuxService;
    /**
     * The {@link TerminalView} shown in  {@link TermuxActivity} that displays the terminal.
     */
    TerminalView mTerminalView;
    /**
     * The {@link TerminalViewClient} interface implementation to allow for communication between
     * {@link TerminalView} and {@link TermuxActivity}.
     */
    TermuxTerminalViewClient mTermuxTerminalViewClient;
    /**
     * The {@link TerminalSessionClient} interface implementation to allow for communication between
     * {@link TerminalSession} and {@link TermuxActivity}.
     */
    TermuxTerminalSessionActivityClient mTermuxTerminalSessionActivityClient;
    /**
     * The last toast shown, used cancel current toast before showing new in {@link #showToast(String, boolean)}.
     */
    Toast mLastToast;
    /**
     * If between onResume() and onStop(). Note that only one session is in the foreground of the terminal view at the
     * time, so if the session causing a change is not in the foreground it should probably be treated as background.
     */
    private boolean mIsVisible;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Delete ReportInfo serialized object files from cache older than 14 days
        // Load Termux app SharedProperties from disk

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_termux);
        // Load termux shared preferences
        // This will also fail if TermuxConstants.TERMUX_PACKAGE_NAME does not equal applicationId
        // Must be done every time activity is created in order to registerForActivityResult,
        // Even if the logic of launching is based on user input.

        setTermuxTerminalViewAndClients();

        registerForContextMenu(mTerminalView);
        setWallpaper();
        try {
            // Start the {@link TermuxService} and make it run regardless of who is bound to it
            Intent serviceIntent = new Intent(this, TermuxService.class);
            startService(serviceIntent);
            // Attempt to bind to the service, this will call the {@link #onServiceConnected(ComponentName, IBinder)}
            // callback if it succeeds.
            if (!bindService(serviceIntent, this, 0))
                throw new RuntimeException("bindService() failed");
        } catch (Exception e) {
            return;
        }
        mTerminalView.onSwipeLTR(() -> getSupportFragmentManager().beginTransaction().replace(R.id.quickNav, Navigation.class, null, "nav").commit());
        // verifyAndroid11ManageFiles();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                try {
                    getSupportFragmentManager().beginTransaction().remove(getSupportFragmentManager().findFragmentByTag("nav")).commit();
                } catch (Exception ignored) {
                }
                try {
                    getSupportFragmentManager().beginTransaction().remove(getSupportFragmentManager().findFragmentByTag("Tt")).commit();
                } catch (Exception ignored) {
                }
                try {
                    getSupportFragmentManager().beginTransaction().remove(getSupportFragmentManager().findFragmentByTag("session")).commit();
                } catch (Exception ignored) {
                }
                mTerminalView.setTouchTransparency(false);
                mTerminalView.setRotaryNavigationMode(0);
            }
        });
    }


    @Override
    public void onStart() {
        super.onStart();
        mIsVisible = true;
        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onStart();
        //registerTermuxActivityBroadcastReceiver();
    }

    @Override
    public void onResume() {
        super.onResume();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onResume();
        // Check if a crash happened on last run of the app or if a plugin crashed and show a
        // notification with the crash details if it did
    }

    public void setWallpaper() {
        String path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).getAbsolutePath() + "/wallpaper.jpeg";
        if (FileUtils.fileExists(path, false))
            getWindow().getDecorView().setBackground(Drawable.createFromPath(path));
    }

    @Override
    protected void onStop() {
        super.onStop();
        mIsVisible = false;

    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mTermuxService != null) {
            // Do not leave service and session clients with references to activity.
            mTermuxService.unsetTermuxTerminalSessionClient();
            mTermuxService = null;
        }
        try {
            unbindService(this);
        } catch (Exception e) {
            // ignore.
        }
    }

    /**
     * Part of the {@link ServiceConnection} interface. The service is bound with
     * {@link #bindService(Intent, ServiceConnection, int)} in {@link #onCreate(Bundle)} which will cause a call to this
     * callback method.
     */
    @Override
    public void onServiceConnected(ComponentName componentName, IBinder service) {
        mTermuxService = ((TermuxService.LocalBinder) service).service;
        final Intent intent = getIntent();
        setIntent(null);
        if (mTermuxService.isTermuxSessionsEmpty()) {
            if (mIsVisible) {
                mTermuxTerminalSessionActivityClient.addNewSession(intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false), null);
                if (intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_PHONE_LISTENER, false))
                    getSupportFragmentManager().beginTransaction().add(R.id.compose_fragment_container, WearReceiverFragment.class, null, "wear").commit();

            } else {
                // The service connected while not in foreground - just bail out.
                finishActivityIfNotFinishing();
            }
        }
        // Update the {@link TerminalSession} and {@link TerminalEmulator} clients.
        mTermuxService.setTermuxTerminalSessionClient(mTermuxTerminalSessionActivityClient);
        String cmd;
        if (intent.getIntExtra("key", 0) != 0)
            getSupportFragmentManager().beginTransaction().add(R.id.compose_fragment_container, ExtraKeysFragment.class, intent.getExtras(), "extra").commit();
        if ((cmd = intent.getStringExtra("cmd")) != null)
            mTerminalView.getCurrentSession().write(cmd + "\r");
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        // Respect being stopped from the {@link TermuxService} notification action.
        finishActivityIfNotFinishing();
    }


    private void setTermuxTerminalViewAndClients() {
        // Set termux terminal view and session clients
        mTermuxTerminalSessionActivityClient = new TermuxTerminalSessionActivityClient(this);
        mTermuxTerminalViewClient = new TermuxTerminalViewClient(this, mTermuxTerminalSessionActivityClient);
        // Set termux terminal view
        mTerminalView = findViewById(R.id.terminal_view);
        mTerminalView.setTerminalViewClient(mTermuxTerminalViewClient);
        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onCreate();

    }

    public void finishActivityIfNotFinishing() {
        // prevent duplicate calls to finish() if called from multiple places
        if (!TermuxActivity.this.isFinishing()) {
            finish();
        }
    }

    /**
     * Show a toast and dismiss the last one if still visible.
     */
    public void showToast(String text, boolean longDuration) {
        if (text == null || text.isEmpty())
            return;
        if (mLastToast != null)
            mLastToast.cancel();
        mLastToast = Toast.makeText(TermuxActivity.this, text, longDuration ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
        mLastToast.show();
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        TerminalSession currentSession = getCurrentSession();
        if (currentSession == null)
            return;
        menu.add(Menu.NONE, CONTEXT_MENU_SELECT_URL_ID, Menu.NONE, R.string.action_select_url);
        menu.add(Menu.NONE, CONTEXT_MENU_SHARE_TRANSCRIPT_ID, Menu.NONE, R.string.action_share_transcript);
        menu.add(Menu.NONE, CONTEXT_MENU_RESET_TERMINAL_ID, Menu.NONE, R.string.action_reset_terminal);
        menu.add(Menu.NONE, CONTEXT_MENU_KILL_PROCESS_ID, Menu.NONE, getResources().getString(R.string.action_kill_process, getCurrentSession().getPid())).setEnabled(currentSession.isRunning());
        menu.add(Menu.NONE, CONTEXT_MENU_REMOVE_BACKGROUND_IMAGE_ID, Menu.NONE, "Remove Background");
        menu.add(Menu.NONE, CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON, Menu.NONE, R.string.action_toggle_keep_screen_on).setCheckable(true).setChecked(true);

    }

    /**
     * Hook system menu to show context menu instead.
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        mTerminalView.showContextMenu();
        return false;
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        TerminalSession session = getCurrentSession();
        return switch (item.getItemId()) {
            case CONTEXT_MENU_SELECT_URL_ID -> {
                mTermuxTerminalViewClient.showUrlSelection();
                yield true;
            }
            case CONTEXT_MENU_SHARE_TRANSCRIPT_ID -> {
                mTermuxTerminalViewClient.shareSessionTranscript();
                yield true;
            }
            case CONTEXT_MENU_RESET_TERMINAL_ID -> {
                onResetTerminalSession(session);
                yield true;
            }
            case CONTEXT_MENU_KILL_PROCESS_ID -> {
                session.finishIfRunning();
                yield true;
            }
            case CONTEXT_MENU_REMOVE_BACKGROUND_IMAGE_ID -> {
                getWindow().getDecorView().setBackgroundColor(Color.BLACK);
                yield true;
            }
            case CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON -> {
                toggleKeepScreenOn();
                yield true;
            }

            default -> super.onContextItemSelected(item);
        };
    }

    @Override
    public void onContextMenuClosed(@NonNull Menu menu) {
        super.onContextMenuClosed(menu);
        // onContextMenuClosed() is triggered twice if back button is pressed to dismiss instead of tap for some reason
        mTerminalView.onContextMenuClosed();
    }


    private void onResetTerminalSession(TerminalSession session) {
        if (session != null) {
            session.reset();
            showToast(getResources().getString(R.string.msg_terminal_reset), true);

        }
    }


    private void toggleKeepScreenOn() {
        mTerminalView.setKeepScreenOn(!mTerminalView.getKeepScreenOn());
    }

    public boolean isVisible() {
        return mIsVisible;
    }


    public TermuxService getTermuxService() {
        return mTermuxService;
    }

    public TerminalView getTerminalView() {
        return mTerminalView;
    }

    public TermuxTerminalSessionActivityClient getTermuxTerminalSessionClient() {
        return mTermuxTerminalSessionActivityClient;
    }

    @Nullable
    public TerminalSession getCurrentSession() {
        if (mTerminalView != null)
            return mTerminalView.getCurrentSession();
        else
            return null;
    }

}
