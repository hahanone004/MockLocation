package fuck.location.xposed.cellar

import android.annotation.SuppressLint
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import fuck.location.app.ui.models.Profile
import fuck.location.xposed.helpers.ConfigGateway
import fuck.location.xposed.helpers.reflect.findAllMethods
import fuck.location.xposed.helpers.reflect.hookBefore

/**
 * What the SIM says it is: the number, the ICCID and the four operator fields.
 *
 * Unlike the cell identity there is mostly nothing here for system_server to
 * intercept. TelephonyManager runs inside the calling app and reads the operator
 * numeric, the alpha tag and the country ISO straight out of the telephony
 * system properties, so those hooks have to be installed in the app's own
 * process - which means the module must be scoped to that app in LSPosed.
 *
 * The number and the ICCID do cross a binder into com.android.phone, so they are
 * hooked there as well and apply to an app whether it is scoped or not.
 *
 * Each hook returns before the real method runs, so an app that lacks
 * READ_PHONE_STATE gets the spoofed value rather than the SecurityException it
 * would otherwise see - which is the point, since the whole purpose is to look
 * like an ordinary SIM. A field the profile has no value for is left alone.
 */
class SimIdentityHooker {

    /** In the target app's own process. */
    @ExperimentalStdlibApi
    @SuppressLint("PrivateApi")
    fun hookTelephonyManager(lpparam: XC_LoadPackage.LoadPackageParam) {
        val packageName = lpparam.packageName

        substitute(lpparam, TELEPHONY_MANAGER, COUNTRY_ISO_METHODS) { it.simCountryIso }
        substitute(lpparam, TELEPHONY_MANAGER, OPERATOR_NUMERIC_METHODS) { it.operatorNumeric }
        substitute(lpparam, TELEPHONY_MANAGER, OPERATOR_NAME_METHODS) { it.simOperatorName }
        substitute(lpparam, TELEPHONY_MANAGER, LINE_NUMBER_METHODS) { it.phoneNumber }
        substitute(lpparam, TELEPHONY_MANAGER, ICCID_METHODS) { it.simSerial }

        // getLine1Number has been deprecated in favour of these two since
        // Android 13, so an app built against a recent SDK never touches
        // TelephonyManager for the number at all.
        substitute(lpparam, SUBSCRIPTION_MANAGER, setOf("getPhoneNumber")) { it.phoneNumber }
        substitute(lpparam, SUBSCRIPTION_INFO, setOf("getNumber")) { it.phoneNumber }
        substitute(lpparam, SUBSCRIPTION_INFO, setOf("getIccId")) { it.simSerial }

        XposedBridge.log("FL: [Cellar] SIM identity hooks armed for $packageName")
    }

    /**
     * In com.android.phone, where the number and the ICCID are actually read off
     * the SIM. These entry points are handed the calling package, so an app can
     * be told apart here without being scoped to the module.
     */
    @ExperimentalStdlibApi
    @SuppressLint("PrivateApi")
    fun hookPhoneProcess(lpparam: XC_LoadPackage.LoadPackageParam) {
        substituteForCaller(lpparam, PHONE_SUB_INFO,
            setOf("getLine1NumberForSubscriber")) { it.phoneNumber }
        substituteForCaller(lpparam, PHONE_SUB_INFO,
            setOf("getIccSerialNumberForSubscriber")) { it.simSerial }
        substituteForCaller(lpparam, PHONE_INTERFACE_MANAGER,
            setOf("getLine1NumberForDisplay")) { it.phoneNumber }
    }

    /**
     * Replaces the result of every String-returning method of [className] named
     * in [names], for the process's own package.
     *
     * Matching on the name alone covers the public method and the hidden
     * per-subscription and per-phone overloads it delegates to; hooking both
     * ends of that delegation is harmless, because the outer one returns before
     * it can call the inner one.
     */
    @ExperimentalStdlibApi
    private fun substitute(
        lpparam: XC_LoadPackage.LoadPackageParam,
        className: String,
        names: Set<String>,
        value: (Profile) -> String,
    ) {
        val packageName = lpparam.packageName

        stringMethods(lpparam, className, names).hookBefore { param ->
            val profile = ConfigGateway.get().simSpoofFor(packageName) ?: return@hookBefore
            val spoofed = value(profile)

            // A field the user never configured is left to the real
            // implementation rather than being blanked out.
            if (spoofed.isNotBlank()) param.result = spoofed
        }
    }

    /** The same, for a binder entry point that names its caller. */
    @ExperimentalStdlibApi
    private fun substituteForCaller(
        lpparam: XC_LoadPackage.LoadPackageParam,
        className: String,
        names: Set<String>,
        value: (Profile) -> String,
    ) {
        stringMethods(lpparam, className, names).hookBefore { param ->
            // callingPackage is the first String parameter of all of these,
            // which survives the subscription and feature id arguments around
            // it being reordered.
            val caller = param.args.filterIsInstance<String>().firstOrNull() ?: return@hookBefore

            val profile = ConfigGateway.get().simSpoofFor(caller) ?: return@hookBefore
            val spoofed = value(profile)

            if (spoofed.isNotBlank()) param.result = spoofed
        }
    }

    private fun stringMethods(
        lpparam: XC_LoadPackage.LoadPackageParam,
        className: String,
        names: Set<String>,
    ): List<java.lang.reflect.Method> {
        val clazz = try {
            lpparam.classLoader.loadClass(className)
        } catch (e: ClassNotFoundException) {
            XposedBridge.log("FL: [Cellar] $className is absent, skipping ${names.joinToString()}")
            return emptyList()
        }

        return findAllMethods(clazz) {
            name in names && returnType == String::class.java
        }
    }

    private companion object {
        const val TELEPHONY_MANAGER = "android.telephony.TelephonyManager"
        const val SUBSCRIPTION_MANAGER = "android.telephony.SubscriptionManager"
        const val SUBSCRIPTION_INFO = "android.telephony.SubscriptionInfo"
        const val PHONE_SUB_INFO = "com.android.phone.PhoneSubInfoController"
        const val PHONE_INTERFACE_MANAGER = "com.android.phone.PhoneInterfaceManager"

        /*
         * The network and the SIM halves of each pair are spoofed from one
         * value: a profile describes a phone sitting on its own home network,
         * where the two agree.
         */
        val COUNTRY_ISO_METHODS = setOf(
            "getNetworkCountryIso", "getNetworkCountryIsoForPhone",
            "getSimCountryIso", "getSimCountryIsoForPhone",
        )
        val OPERATOR_NUMERIC_METHODS = setOf(
            "getNetworkOperator", "getNetworkOperatorForPhone",
            "getSimOperator", "getSimOperatorNumeric", "getSimOperatorNumericForPhone",
        )
        val OPERATOR_NAME_METHODS = setOf(
            "getNetworkOperatorName", "getNetworkOperatorNameForPhone",
            "getSimOperatorName", "getSimOperatorNameForPhone",
        )
        val LINE_NUMBER_METHODS = setOf("getLine1Number")
        val ICCID_METHODS = setOf("getSimSerialNumber", "getIccSerialNumber")
    }
}
