package acr.browser.lightning.accel

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel

/** The bound main-process owner also proves listener readiness to the incognito process. */
class NativeProxyService : Service() {
    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != READY) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(DESCRIPTOR)
            reply?.writeNoException()
            reply?.writeInt(if (DshaAccel.isNativeOwnerReady()) 1 else 0)
            return true
        }
    }

    override fun onCreate() {
        super.onCreate()
        DshaAccel.start(this)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        DshaAccel.stop()
        super.onDestroy()
    }

    companion object {
        private const val DESCRIPTOR = "acr.browser.lightning.accel.NativeProxyService"
        private const val READY = IBinder.FIRST_CALL_TRANSACTION

        internal fun isReady(service: IBinder): Boolean {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeInterfaceToken(DESCRIPTOR)
                if (!service.transact(READY, data, reply, 0)) false else {
                    reply.readException()
                    reply.readInt() == 1
                }
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
    }
}
