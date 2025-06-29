import android.content.Context
import android.os.IBinder
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager

class MyPhoneStateListener(private val context: Context) : PhoneStateListener() {
    var onCallStateChanged: ((Int, String?) -> Unit)? = null
    private val telephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    @Deprecated("Deprecated in Java")
    override fun onCallStateChanged(state: Int, incomingNumber: String?) {
        super.onCallStateChanged(state, incomingNumber)
        onCallStateChanged?.invoke(state, incomingNumber)
    }

    fun disconnect() {
        try {
            val itelephony = Class.forName("com.android.internal.telephony.ITelephony")
                .getMethod("Stub.asInterface", IBinder::class.java).invoke(
                    null,
                    telephonyManager.javaClass.getMethod("getITelephony").invoke(telephonyManager)
                )
            itelephony.javaClass.getMethod("endCall").invoke(itelephony)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}