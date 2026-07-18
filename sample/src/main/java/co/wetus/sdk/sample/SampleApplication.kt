package co.wetus.sdk.sample

import android.app.Application
import co.wetus.sdk.WtsSdk

class SampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        WtsSdk.configure(this, SampleConfiguration.appKey)
    }
}
