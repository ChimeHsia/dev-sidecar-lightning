package acr.browser.lightning.accel

import acr.browser.lightning.SDK_VERSION
import acr.browser.lightning.TestApplication
import android.os.Binder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import android.os.IBinder
import android.os.Parcel
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [SDK_VERSION])
class NativeProxyServiceTest {
    @Test fun serviceBindingAloneDoesNotClaimAnUnstartedCoreIsReady() {
        val binder = NativeProxyService().onBind(null)
        assertFalse(NativeProxyService.isReady(binder))
    }

    @Test fun actualOwnerPortIsPropagatedInsteadOfAnAssumedDefault() {
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                reply!!.writeNoException()
                reply.writeInt(31184)
                return true
            }
        }
        assertEquals(31184, NativeProxyService.readyPort(binder))
    }

    @Test fun unrelatedBinderCannotSupplyCoreReadiness() {
        assertFalse(NativeProxyService.isReady(Binder()))
    }
}
