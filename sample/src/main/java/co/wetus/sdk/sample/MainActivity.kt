package co.wetus.sdk.sample

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import co.wetus.sdk.WtsSdk
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).also { setContentView(it) }
        lifecycleScope.launch {
            val direct = intent.data?.let { runCatching { WtsSdk.shared().handle(it) }.getOrNull() }
            val result = direct ?: WtsSdk.shared().getDeferredDeepLink()
            status.text = result?.let { "Resolved ${it.path}" } ?: "No deep link"
        }
    }
}
