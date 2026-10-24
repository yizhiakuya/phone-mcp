package top.rainaki.phonemcp.ui

import android.os.Bundle
import android.view.WindowManager
import com.journeyapps.barcodescanner.CaptureActivity
import top.rainaki.phonemcp.PhoneMcpApp

/** 配对扫码期间也阻止截图与远程动作，避免摄像头画面泄漏配对码。 */
class PairingCaptureActivity : CaptureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        (application as PhoneMcpApp).settingsVisible = true
    }

    override fun onPause() {
        (application as PhoneMcpApp).settingsVisible = false
        super.onPause()
    }
}
