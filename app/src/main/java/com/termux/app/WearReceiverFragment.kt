package com.termux.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import java.io.InputStream

class WearReceiverFragment : Fragment(),
    MessageClient.OnMessageReceivedListener,DataClient.OnDataChangedListener {
    private var mActivity: TermuxActivity? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mActivity = activity as TermuxActivity

    }

    override fun onMessageReceived(p0: MessageEvent) {
        var text = String(p0.data)
        if (p0.path == "/cmd") {
            if (text.isEmpty())
                text = "\r"
            mActivity!!.currentSession!!.write(text)
        } else if (p0.path == "/key")
            mActivity!!.terminalView.handleKeyCode(text.toInt(), KeyEvent.ACTION_DOWN)
    }

    override fun onResume() {
        super.onResume()
        Wearable.getMessageClient(mActivity!!).addListener(this)
    }

    override fun onPause() {
        super.onPause()
        Wearable.getMessageClient(mActivity!!).removeListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        Toast.makeText(mActivity, "stopped", Toast.LENGTH_SHORT).show()
    }

    override fun onDataChanged(p0: DataEventBuffer) {
        p0.filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == "/image" }
            .forEach { event ->
                val bitmap: Bitmap? = DataMapItem.fromDataItem(event.dataItem)
                    .dataMap.getAsset("profileImage")
                    .let { asset -> loadBitmapFromAsset(asset!!) }
                // Do something with the bitmap
                mActivity!!.getmTermuxBackgroundManager().updateBackground(bitmap)
            }
    }
    private fun loadBitmapFromAsset(asset: Asset): Bitmap? {
        // Convert asset into a file descriptor and block until it's ready
        val assetInputStream: InputStream? =
            Tasks.await(Wearable.getDataClient(mActivity!!).getFdForAsset(asset))
                ?.inputStream

        return assetInputStream?.let { inputStream ->
            // Decode the stream into a bitmap
            BitmapFactory.decodeStream(inputStream)
        }
    }
}
