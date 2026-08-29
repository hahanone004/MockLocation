package mock.location.xposed.cellar

import android.annotation.SuppressLint
import de.robv.android.xposed.callbacks.XC_LoadPackage
import mock.location.app.ui.models.Profile
import mock.location.xposed.helpers.ConfigGateway
import mock.location.xposed.helpers.reflect.Log
import mock.location.xposed.helpers.reflect.findAllMethods
import mock.location.xposed.helpers.reflect.hookMethod

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
 * Each hook replaces only a successful result. Permission failures from the
 * stock implementation remain failures, matching the platform's API contract.
 * A field the profile has no value for is left alone.
 */
class SimIdentityHooker {

    /** In the target app's own process. */
    @ExperimentalStdlibApi
    @SuppressLint("PrivateApi")
    fun hookTelephonyManager(lpparam: XC_LoadPackage.LoadPackageParam) {
        val packageName = lpparam.packageName

        var installed = 0
        installed += substitute(lpparam, TELEPHONY_MANAGER, COUNTRY_ISO_METHODS) { it.simCountryIso }
        installed += substitute(lpparam, TELEPHONY_MANAGER, OPERATOR_NUMERIC_METHODS) { it.operatorNumeric }
        installed += substitute(lpparam, TELEPHONY_MANAGER, OPERATOR_NAME_METHODS) { it.simOperatorName }
        installed += substitute(lpparam, TELEPHONY_MANAGER, LINE_NUMBER_METHODS) { it.phoneNumber }
        installed += substitute(lpparam, TELEPHONY_MANAGER, ICCID_METHODS) { it.simSerial }

        // getLine1Number has been deprecated in favour of these two since
        // Android 13, so an app built against a recent SDK never touches
        // TelephonyManager for the number at all.
        installed += substitute(lpparam, SUBSCRIPTION_MANAGER, setOf("getPhoneNumber")) { it.phoneNumber }
        installed += substitute(lpparam, SUBSCRIPTION_INFO, setOf("getNumber")) { it.phoneNumber }
        installed += substitute(lpparam, SUBSCRIPTION_INFO, setOf("getIccId")) { it.simSerial }

        if (installed == 0) {
            throw NoSuchMethodException("no SIM identity methods for $packageName")
        }

        Log.i("[Cellar] SIM identity hooks armed for $packageName")
    }

    /**
     * In com.android.phone, where the number and the ICCID are actually read off
     * the SIM. These entry points are handed the calling package, so an app can
     * be told apart here without being scoped to the module.
     */
    @ExperimentalStdlibApi
    @SuppressLint("PrivateApi")
    fun hookPhoneProcess(lpparam: XC_LoadPackage.LoadPackageParam) {
        var installed = 0
        installed += substituteForCaller(lpparam, PHONE_SUB_INFO, PHONE_SUB_INFO_NUMBER) { it.phoneNumber }
        installed += substituteForCaller(lpparam, PHONE_SUB_INFO, PHONE_SUB_INFO_ICCID) { it.simSerial }
        installed += substituteForCaller(lpparam, PHONE_INTERFACE_MANAGER,
            setOf("getLine1NumberForDisplay")) { it.phoneNumber }
        if (installed == 0) {
            throw NoSuchMethodException("no phone-process SIM identity methods")
        }
    }

    /**
     * Replaces the result of every String-returning method of [className] named
     * in [names], for the process's own package.
     *
     * Matching on the name alone covers the public method and the hidden
     * per-subscription and per-phone overloads it delegates to; hooking both
     * ends of that delegation is harmless: both see the same profile and
     * replace only a successful String result.
     */
    @ExperimentalStdlibApi
    private fun substitute(
        lpparam: XC_LoadPackage.LoadPackageParam,
        className: String,
        names: Set<String>,
        value: (Profile) -> String,
    ): Int {
        val packageName = lpparam.packageName

        val methods = stringMethods(lpparam, className, names)
        methods.hookMethod {
            after { param ->
                if (param.hasThrowable()) return@after
                val profile = ConfigGateway.get().simSpoofFor(packageName) ?: return@after
                val spoofed = value(profile)

                // A field the user never configured is left to the real
                // implementation rather than being blanked out.
                if (spoofed.isNotBlank()) param.result = spoofed
            }
        }
        return methods.size
    }

    /** The same, for a binder entry point that names its caller. */
    @ExperimentalStdlibApi
    private fun substituteForCaller(
        lpparam: XC_LoadPackage.LoadPackageParam,
        className: String,
        names: Set<String>,
        value: (Profile) -> String,
    ): Int {
        val methods = stringMethods(lpparam, className, names)
        methods.hookMethod {
            after { param ->
                if (param.hasThrowable()) return@after
                // Pick the String that actually resolves to a configured app;
                // feature and attribution tags may surround it on newer APIs.
                val matched = ConfigGateway.get()
                    .spoofedCaller(param) { ConfigGateway.get().simSpoofFor(it) }
                    ?: return@after
                val spoofed = value(matched.second)

                if (spoofed.isNotBlank()) param.result = spoofed
            }
        }
        return methods.size
    }

    private fun stringMethods(
        lpparam: XC_LoadPackage.LoadPackageParam,
        className: String,
        names: Set<String>,
    ): List<java.lang.reflect.Method> {
        val clazz = try {
            lpparam.classLoader.loadClass(className)
        } catch (e: ClassNotFoundException) {
            Log.w("[Cellar] $className is absent, skipping ${names.joinToString()}")
            return emptyList()
        }

        val methods = findAllMethods(clazz, findSuper = true) {
            name in names && returnType == String::class.java
        }
        if (methods.isEmpty()) {
            Log.i("[Cellar] no ${names.joinToString()} methods in $className")
        }
        return methods
    }

    private companion object {
        const val TELEPHONY_MANAGER = "android.telephony.TelephonyManager"
        const val SUBSCRIPTION_MANAGER = "android.telephony.SubscriptionManager"
        const val SUBSCRIPTION_INFO = "android.telephony.SubscriptionInfo"
        /*
         * Hosted by the phone process but declared in the telephony framework,
         * not in the phone app: looking for it under com.android.phone found
         * nothing, and the number and the ICCID went unspoofed for every app
         * the module was not scoped to.
         */
        const val PHONE_SUB_INFO = "com.android.internal.telephony.PhoneSubInfoController"
        const val PHONE_INTERFACE_MANAGER = "com.android.phone.PhoneInterfaceManager"

        // The plain and WithFeature forms delegate to the ForSubscriber ones,
        // so hooking the delegate alone would do - but all three carry the
        // calling package in the same place, and covering them costs nothing if
        // that delegation ever changes.
        val PHONE_SUB_INFO_NUMBER = setOf("getLine1Number", "getLine1NumberForSubscriber")
        val PHONE_SUB_INFO_ICCID = setOf(
            "getIccSerialNumber", "getIccSerialNumberWithFeature",
            "getIccSerialNumberForSubscriber",
        )

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
