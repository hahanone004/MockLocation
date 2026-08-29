package fuck.location.xposed.cellar

import android.annotation.SuppressLint
import android.telephony.*
import fuck.location.xposed.helpers.reflect.*
import fuck.location.xposed.helpers.reflect.findAllMethods
import de.robv.android.xposed.XposedBridge
import fuck.location.xposed.cellar.identity.Lte
import fuck.location.xposed.cellar.identity.Nr
import fuck.location.xposed.helpers.ConfigGateway

class TelephonyRegistryHooker {
    @ExperimentalStdlibApi
    @SuppressLint("PrivateApi")
    fun hookListen(classLoader: ClassLoader) {
        val clazz: Class<*> =
            classLoader.loadClass("com.android.server.TelephonyRegistry")

        findAllMethods(clazz) {
            name == "validateEventAndUserLocked" && isPrivate
        }.hookAfter { param ->
            val record = param.args[0]
            val event = param.args[1] as Int

            val packageName = findField(record.javaClass) {
                name == "callingPackage"
            }.get(record) as String

            val shouldReportOrigin = param.result as Boolean

            val profile = ConfigGateway.get().cellSpoofFor(packageName)
            if (profile != null && shouldReportOrigin) {
                val callBack = findField(record.javaClass) {
                    name == "callback"
                }.get(record)

                val phoneId = findField(record.javaClass) {
                    name == "phoneId"
                }.get(record)

                when (event) {
                    5 -> {
                        XposedBridge.log("FL: [Cellar] in whiteList! Alter EVENT_CELL_LOCATION_CHANGED for now.")

                        if (phoneId != null) {
                            val mCellIdentity = findField(param.thisObject.javaClass) {
                                name == "mCellIdentity"
                            }.get(param.thisObject)
                            if (mCellIdentity != null) {
                                if ((phoneId as Int) >= 0 && phoneId < (mCellIdentity as Array<*>).size) {
                                    val originalCellIdentity = mCellIdentity[phoneId]
                                    if (originalCellIdentity != null) {
                                        when (originalCellIdentity) {
                                            is CellIdentityLte -> {
                                                findMethod(callBack.javaClass) {
                                                    name == "onCellLocationChanged"
                                                }.invoke(
                                                    callBack,
                                                    Lte().cellIdentity(profile, originalCellIdentity)
                                                )
                                            }
                                            is CellIdentityNr -> {
                                                findMethod(callBack.javaClass) {
                                                    name == "onCellLocationChanged"
                                                }.invoke(
                                                    callBack,
                                                    Nr().alterCellIdentity(originalCellIdentity, profile)
                                                )
                                            }
                                            else -> {
                                                findMethod(callBack.javaClass) {
                                                    name == "onCellLocationChanged"
                                                }.invoke(callBack, null)
                                            }
                                        }
                                    } else {
                                        findMethod(callBack.javaClass) {
                                            name == "onCellLocationChanged"
                                        }.invoke(callBack, null)
                                    }
                                }
                            }
                        }

                        param.result = false
                    }

                    11 -> {
                        XposedBridge.log("FL: [Cellar] in whiteList! Alter EVENT_CELL_INFO_CHANGED for now.")

                        if (phoneId != null) {
                            val mCellInfo = findField(param.thisObject.javaClass) {
                                name == "mCellInfo"
                            }.get(param.thisObject)

                            if (mCellInfo != null) {
                                if ((phoneId as Int) >= 0 && phoneId < (mCellInfo as ArrayList<*>).size) {
                                    val originalCellInfoList = mCellInfo[phoneId]
                                    if (originalCellInfoList != null) {
                                        val modifiedCellInfoList = mutableListOf<CellInfo>()

                                        (originalCellInfoList as List<*>).forEach { cellInfo ->
                                            if (cellInfo != null) {
                                                when (cellInfo) {
                                                    is CellInfoLte -> {
                                                        modifiedCellInfoList.add(
                                                            fuck.location.xposed.cellar.info.Lte()
                                                                .cellInfo(profile, cellInfo)
                                                        )
                                                    }
                                                    is CellInfoNr -> {
                                                        modifiedCellInfoList.add(
                                                            fuck.location.xposed.cellar.info.Nr()
                                                                .constructNewCellInfoNr(cellInfo, profile)
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        findMethod(callBack.javaClass) {
                                            name == "onCellInfoChanged"
                                        }.invoke(callBack, modifiedCellInfoList)    // return cellInfo
                                    } else {
                                        findMethod(callBack.javaClass) {
                                            name == "onCellInfoChanged"
                                        }.invoke(callBack, null)
                                    }
                                }
                            } else {
                                findMethod(callBack.javaClass) {
                                    name == "onCellInfoChanged"
                                }.invoke(callBack, null)
                            }
                        }

                        param.result = false
                    }
                }
            }
        }

        // Do not filter mRecords here. It is the registry's persistent listener
        // table, shared by every telephony event. The validate hook above
        // delivers the substituted cell callback and suppresses only that one
        // original delivery; removing its Record would also unsubscribe the app
        // from every later signal, service-state and call-state notification.
    }
}
