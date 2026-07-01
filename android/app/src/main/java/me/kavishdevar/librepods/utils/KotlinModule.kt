package me.kavishdevar.librepods.utils

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import android.widget.ImageView
import androidx.core.net.toUri
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Method

private const val TAG = "LibrePodsHook"
private const val XIAOMI_AIRPODS_AACP_PSM = 0x1001
private const val BLUETOOTH_SOCKET_TYPE_L2CAP_BREDR = 3
private const val ANDROID_BLUETOOTH_UID = 1002
private const val XIAOMI_BLUETOOTH_PACKAGE = "com.xiaomi.bluetooth"
private const val L2C_FCR_HOOK_SO = "libl2c_fcr_hook.so"
private const val DEXKIT_SO = "libdexkit.so"

@SuppressLint("DiscouragedApi", "PrivateApi")
class KotlinModule: XposedModule() {
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "module initialized at :: ${param.processName}")
        log(Log.INFO, TAG, "framework: $frameworkName($frameworkVersionCode) API $apiVersion")
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    override fun onPackageLoaded(param: PackageLoadedParam) {
        log(Log.INFO, TAG, "onPackageLoaded :: ${param.packageName}")

        if (param.packageName == "com.google.android.bluetooth" || param.packageName == "com.android.bluetooth") {
            log(Log.INFO, TAG, "Bluetooth app detected, hooking l2c_fcr_chk_chan_modes")
            try {
                if (param.isFirstPackage) {
                    if (!loadModuleNativeLibrary(L2C_FCR_HOOK_SO)) {
                        log(Log.ERROR, TAG, "Could not load $L2C_FCR_HOOK_SO from base or splits")
                        return
                    }

                    val remotePrefValue = getRemotePreferences("me.kavishdevar.librepods").getBoolean("vendor_id_hook", false)
                    log(Log.INFO, TAG, "sdp hook enabled (remote pref): $remotePrefValue")
                    NativeBridge.setSdpHook(remotePrefValue)
                    log(Log.INFO, TAG, "Native library loaded successfully")
                }
            } catch (e: Exception) {
                log(Log.ERROR, TAG, "Failed to load native library: ${e.message}")
            }

            hookBluetoothSocketManagerBinder(param)
        }

        if (param.packageName == XIAOMI_BLUETOOTH_PACKAGE) {
            hookXiaomiBluetoothExtension(param)
        }

        if (param.packageName == "com.google.android.settings") {
            hookSettingsController(param, "com.google.android.settings.bluetooth.AdvancedBluetoothDetailsHeaderController")
        }

        if (param.packageName == "com.android.settings") {
            hookSettingsController(param, "com.android.settings.bluetooth.AdvancedBluetoothDetailsHeaderController")
        }
    }

    private fun hookBluetoothSocketManagerBinder(param: PackageLoadedParam) {
        try {
            val socketManagerClass = Class.forName(
                "com.android.bluetooth.btservice.BluetoothSocketManagerBinder",
                false,
                param.defaultClassLoader
            )
            val connectSocketMethods = socketManagerClass.declaredMethods.filter { method ->
                method.name == "connectSocket" &&
                        method.parameterTypes.any { BluetoothDevice::class.java.isAssignableFrom(it) } &&
                        method.parameterTypes.count { it == Integer.TYPE } >= 2
            }

            if (connectSocketMethods.isEmpty()) {
                log(Log.WARN, TAG, "BluetoothSocketManagerBinder#connectSocket target not found")
                return
            }

            connectSocketMethods.forEach { connectSocketMethod ->
                connectSocketMethod.isAccessible = true
                hook(connectSocketMethod).intercept { chain ->
                    val args = parseConnectSocketArgs(chain.args)
                    val callingUid = Binder.getCallingUid()
                    val callingPid = Binder.getCallingPid()

                    if (shouldSuppressXiaomiBinderAacpConnect(args.device, args.socketType, args.uuid, args.port, callingUid)) {
                        log(
                            Log.INFO,
                            TAG,
                            "Suppressed Xiaomi binder AACP connect uid/pid=$callingUid/$callingPid " +
                                    "type=${args.socketType} port=${args.port} flag=${args.flag} uuid=${args.uuid} " +
                                    "device=${describeBluetoothDevice(args.device)} method=${connectSocketMethod.parameterTypes.joinToString { it.simpleName }}"
                        )
                        suppressedReturnValue(connectSocketMethod.returnType)
                    } else {
                        chain.proceed()
                    }
                }
                log(Log.INFO, TAG, "Hooked BluetoothSocketManagerBinder#connectSocket ${connectSocketMethod.parameterTypes.joinToString { it.simpleName }}")
            }
        } catch (e: Throwable) {
            log(Log.WARN, TAG, "Failed to hook BluetoothSocketManagerBinder#connectSocket: ${e.message}")
        }
    }

    private data class ConnectSocketArgs(
        val device: BluetoothDevice?,
        val socketType: Int?,
        val uuid: ParcelUuid?,
        val port: Int?,
        val flag: Int?
    )

    private fun suppressedReturnValue(returnType: Class<*>): Any? {
        if (!returnType.isPrimitive) return null
        return when (returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Character.TYPE -> 0.toChar()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Void.TYPE -> null
            else -> null
        }
    }

    private fun parseConnectSocketArgs(args: List<Any?>): ConnectSocketArgs {
        val intArgs = args.mapNotNull { it as? Int }
        return ConnectSocketArgs(
            device = args.firstOrNull { it is BluetoothDevice } as? BluetoothDevice,
            socketType = intArgs.getOrNull(0),
            uuid = args.firstOrNull { it is ParcelUuid } as? ParcelUuid,
            port = intArgs.getOrNull(1),
            flag = intArgs.getOrNull(2)
        )
    }

    private fun loadModuleNativeLibrary(soName: String): Boolean {
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: return false
        val candidates = buildList {
            add("${moduleApplicationInfo.sourceDir}!/lib/$abi/$soName")
            moduleApplicationInfo.splitSourceDirs?.forEach { split ->
                add("$split!/lib/$abi/$soName")
            }
        }

        for (path in candidates) {
            try {
                log(Log.INFO, TAG, "Trying to load native lib from $path")
                System.load(path)
                log(Log.INFO, TAG, "Loaded native lib from $path")
                return true
            } catch (e: Throwable) {
                val alreadyLoaded = e.message?.contains("already loaded", ignoreCase = true) == true
                if (alreadyLoaded) {
                    log(Log.INFO, TAG, "$soName already loaded")
                    return true
                }
                log(Log.WARN, TAG, "Failed to load from $path: ${e.message}")
            }
        }

        return false
    }

    private fun hookXiaomiBluetoothExtension(param: PackageLoadedParam) {
        log(Log.INFO, TAG, "Xiaomi Bluetooth Extension detected, installing DexKit AirPods L2CAP compatibility hooks")

        var sendConnectHooked = false
        var handlerConnectHooked = false
        var connectorCreateSocketHooked = false
        val frameworkSocketHooked = hookXiaomiBluetoothDeviceL2capCreation()

        if (loadModuleNativeLibrary(DEXKIT_SO)) {
            try {
                DexKitBridge.create(param.defaultClassLoader, true).use { bridge ->
                    findXiaomiAirCoreSendConnectMethod(bridge)?.let { methodData ->
                        sendConnectHooked = hookXiaomiMethodData(
                            param,
                            methodData,
                            "Xiaomi AirCore sendConnectMsg"
                        ) { chain ->
                            val device = chain.args.getOrNull(0) as? BluetoothDevice
                            if (shouldSuppressXiaomiAirPodsL2cap(device, XIAOMI_AIRPODS_AACP_PSM)) {
                                log(Log.INFO, TAG, "Suppressed Xiaomi AirCore sendConnectMsg for ${describeBluetoothDevice(device)}")
                                null
                            } else {
                                chain.proceed()
                            }
                        }
                    }

                    findXiaomiDevicesTransportConnectMethod(bridge)?.let { methodData ->
                        handlerConnectHooked = hookXiaomiMethodData(
                            param,
                            methodData,
                            "Xiaomi DevicesTransportHandler L2CAP connect"
                        ) { chain ->
                            val device = chain.args.getOrNull(0) as? BluetoothDevice
                            val psm = chain.args.getOrNull(1) as? Int
                            if (shouldSuppressXiaomiAirPodsL2cap(device, psm)) {
                                log(Log.INFO, TAG, "Suppressed Xiaomi DevicesTransportHandler L2CAP connect psm=$psm device=${describeBluetoothDevice(device)}")
                                null
                            } else {
                                chain.proceed()
                            }
                        }
                    }

                    findXiaomiConnectorCreateL2capSocketMethod(bridge)?.let { methodData ->
                        connectorCreateSocketHooked = hookXiaomiMethodData(
                            param,
                            methodData,
                            "Xiaomi Connector createL2capSocket"
                        ) { chain ->
                            val device = chain.args.getOrNull(0) as? BluetoothDevice
                            val psm = chain.args.getOrNull(1) as? Int
                            if (shouldSuppressXiaomiAirPodsL2cap(device, psm)) {
                                log(Log.INFO, TAG, "Suppressed Xiaomi Connector createL2capSocket psm=$psm device=${describeBluetoothDevice(device)}")
                                null
                            } else {
                                chain.proceed()
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                log(Log.WARN, TAG, "DexKit Xiaomi hook lookup failed: ${e.message}")
            }
        } else {
            log(Log.WARN, TAG, "DexKit native library unavailable; falling back to known Xiaomi class names")
        }

        if (!sendConnectHooked) {
            sendConnectHooked = hookXiaomiMethodByKnownNames(
                param,
                listOf("l1.b", "l1.C1554b"),
                "q",
                BluetoothDevice::class.java,
            ) { chain ->
                val device = chain.args.getOrNull(0) as? BluetoothDevice
                if (shouldSuppressXiaomiAirPodsL2cap(device, XIAOMI_AIRPODS_AACP_PSM)) {
                    log(Log.INFO, TAG, "Suppressed Xiaomi AirCore sendConnectMsg for ${describeBluetoothDevice(device)}")
                    null
                } else {
                    chain.proceed()
                }
            }
        }

        if (!handlerConnectHooked) {
            handlerConnectHooked = hookXiaomiMethodByKnownNames(
                param,
                listOf("l1.e", "l1.HandlerC1557e"),
                "b",
                BluetoothDevice::class.java,
                Integer.TYPE,
            ) { chain ->
                val device = chain.args.getOrNull(0) as? BluetoothDevice
                val psm = chain.args.getOrNull(1) as? Int
                if (shouldSuppressXiaomiAirPodsL2cap(device, psm)) {
                    log(Log.INFO, TAG, "Suppressed Xiaomi DevicesTransportHandler L2CAP connect psm=$psm device=${describeBluetoothDevice(device)}")
                    null
                } else {
                    chain.proceed()
                }
            }
        }

        if (!connectorCreateSocketHooked) {
            connectorCreateSocketHooked = hookXiaomiMethodByKnownNames(
                param,
                listOf("l1.i"),
                "g",
                BluetoothDevice::class.java,
                Integer.TYPE,
            ) { chain ->
                val device = chain.args.getOrNull(0) as? BluetoothDevice
                val psm = chain.args.getOrNull(1) as? Int
                if (shouldSuppressXiaomiAirPodsL2cap(device, psm)) {
                    log(Log.INFO, TAG, "Suppressed Xiaomi Connector createL2capSocket psm=$psm device=${describeBluetoothDevice(device)}")
                    null
                } else {
                    chain.proceed()
                }
            }
        }

        log(
            Log.INFO,
            TAG,
            "Xiaomi compatibility hook result: sendConnect=$sendConnectHooked " +
                    "handlerConnect=$handlerConnectHooked connectorCreateSocket=$connectorCreateSocketHooked " +
                    "frameworkSocket=$frameworkSocketHooked"
        )
    }

    private fun hookXiaomiBluetoothDeviceL2capCreation(): Boolean {
        var hooked = false
        listOf("createL2capSocket", "createInsecureL2capSocket").forEach { methodName ->
            try {
                val method = BluetoothDevice::class.java.getDeclaredMethod(methodName, Integer.TYPE)
                method.isAccessible = true
                hook(method).intercept { chain ->
                    val device = chain.thisObject as? BluetoothDevice
                    val psm = chain.args.getOrNull(0) as? Int

                    if (shouldSuppressXiaomiAirPodsL2cap(device, psm)) {
                        log(
                            Log.INFO,
                            TAG,
                            "Suppressed Xiaomi BluetoothDevice#$methodName psm=$psm device=${describeBluetoothDevice(device)}"
                        )
                        null
                    } else {
                        chain.proceed()
                    }
                }
                log(Log.INFO, TAG, "Hooked Xiaomi BluetoothDevice#$methodName guard")
                hooked = true
            } catch (e: NoSuchMethodException) {
                log(Log.WARN, TAG, "BluetoothDevice#$methodName is not available")
            } catch (e: Throwable) {
                log(Log.WARN, TAG, "Failed to hook BluetoothDevice#$methodName: ${e.message}")
            }
        }
        return hooked
    }

    private fun findXiaomiAirCoreSendConnectMethod(bridge: DexKitBridge): MethodData? {
        val matches = listOf("sendConnectMsg", "sendConnectMsg ").flatMap { sendConnectString ->
            bridge.findMethod {
                matcher {
                    returnType("void")
                    paramTypes("android.bluetooth.BluetoothDevice")
                    usingStrings("AirCoreManager", sendConnectString)
                    usingNumbers(XIAOMI_AIRPODS_AACP_PSM)
                }
            }
        }.distinctBy { it.methodSign }

        return selectBestMethod(matches, "Xiaomi AirCore sendConnectMsg")
    }

    private fun findXiaomiDevicesTransportConnectMethod(bridge: DexKitBridge): MethodData? {
        val matches = listOf("handleConnectL2capMsg", "handleConnectL2capMsg ").flatMap { connectString ->
            bridge.findMethod {
                matcher {
                    returnType("void")
                    paramTypes("android.bluetooth.BluetoothDevice", "int")
                    usingStrings(
                        "DevicesTransportHandler",
                        connectString,
                        "Utils.addToWhitelist"
                    )
                }
            }
        }.distinctBy { it.methodSign }

        return selectBestMethod(matches, "Xiaomi DevicesTransportHandler L2CAP connect")
    }

    private fun findXiaomiConnectorCreateL2capSocketMethod(bridge: DexKitBridge): MethodData? {
        val matches = bridge.findMethod {
            matcher {
                returnType("android.bluetooth.BluetoothSocket")
                paramTypes("android.bluetooth.BluetoothDevice", "int")
                usingStrings("Connector", "createL2capSocket e:")
            }
        }

        return selectBestMethod(matches, "Xiaomi Connector createL2capSocket")
    }

    private fun selectBestMethod(matches: List<MethodData>, label: String): MethodData? {
        if (matches.isEmpty()) {
            log(Log.WARN, TAG, "DexKit target not found: $label")
            return null
        }

        val selected = matches.first()
        if (matches.size > 1) {
            log(
                Log.WARN,
                TAG,
                "DexKit target not unique for $label; using ${selected.methodSign}, all=${matches.joinToString { it.methodSign }}"
            )
        } else {
            log(Log.INFO, TAG, "DexKit resolved $label -> ${selected.methodSign}")
        }
        return selected
    }

    private fun hookXiaomiMethodData(
        param: PackageLoadedParam,
        methodData: MethodData,
        label: String,
        callback: (XposedInterface.Chain) -> Any?
    ): Boolean {
        return try {
            val method = methodData.getMethodInstance(param.defaultClassLoader)
            hookXiaomiMethod(method, label, callback)
        } catch (e: Throwable) {
            log(Log.WARN, TAG, "Failed to materialize DexKit method for $label (${methodData.methodSign}): ${e.message}")
            false
        }
    }

    private fun hookXiaomiMethodByKnownNames(
        param: PackageLoadedParam,
        classNames: List<String>,
        methodName: String,
        vararg parameterTypes: Class<*>,
        callback: (XposedInterface.Chain) -> Any?
    ): Boolean {
        var hooked = false

        classNames.forEach { className ->
            val targetClass = try {
                Class.forName(className, false, param.defaultClassLoader)
            } catch (_: Throwable) {
                null
            } ?: return@forEach

            try {
                val method = targetClass.getDeclaredMethod(methodName, *parameterTypes)
                hooked = hookXiaomiMethod(method, "${targetClass.name}#$methodName", callback) || hooked
            } catch (_: NoSuchMethodException) {
                log(Log.WARN, TAG, "Xiaomi class ${targetClass.name} has no method $methodName with expected signature")
            } catch (e: Throwable) {
                log(Log.WARN, TAG, "Failed to hook Xiaomi method ${targetClass.name}#$methodName: ${e.message}")
            }
        }

        if (!hooked) {
            log(Log.WARN, TAG, "Xiaomi hook target not found: ${classNames.joinToString("/")}")
        }
        return hooked
    }

    private fun hookXiaomiMethod(
        method: Method,
        label: String,
        callback: (XposedInterface.Chain) -> Any?
    ): Boolean {
        return try {
            method.isAccessible = true
            hook(method).intercept { chain -> callback(chain) }
            log(Log.INFO, TAG, "Hooked $label -> ${method.declaringClass.name}#${method.name}")
            true
        } catch (e: Throwable) {
            log(Log.WARN, TAG, "Failed to hook $label: ${e.message}")
            false
        }
    }

    private fun shouldSuppressXiaomiAirPodsL2cap(device: BluetoothDevice?, psm: Int?): Boolean {
        if (psm != XIAOMI_AIRPODS_AACP_PSM) return false
        if (!isVendorIdHookEnabled()) return false
        return looksLikeAirPods(device)
    }

    private fun shouldSuppressXiaomiBinderAacpConnect(
        device: BluetoothDevice?,
        socketType: Int?,
        uuid: ParcelUuid?,
        port: Int?,
        callingUid: Int
    ): Boolean {
        if (!isXiaomiDevice()) return false
        if (!isXiaomiBluetoothCaller(callingUid)) return false
        if (socketType != BLUETOOTH_SOCKET_TYPE_L2CAP_BREDR) return false
        if (port != XIAOMI_AIRPODS_AACP_PSM) return false
        if (!isNullOrZeroUuid(uuid)) return false
        if (!isVendorIdHookEnabled()) return false
        return looksLikeAirPods(device)
    }

    private fun isXiaomiBluetoothCaller(callingUid: Int): Boolean {
        val packages = getPackagesForUid(callingUid)
        if (packages != null) {
            return packages.any { it == XIAOMI_BLUETOOTH_PACKAGE || it.startsWith("$XIAOMI_BLUETOOTH_PACKAGE.") }
        }

        return callingUid == ANDROID_BLUETOOTH_UID
    }

    private fun getPackagesForUid(uid: Int): Array<String>? {
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentApplication = activityThreadClass
                .getDeclaredMethod("currentApplication")
                .invoke(null) as? Context
            currentApplication?.packageManager?.getPackagesForUid(uid)
        } catch (e: Throwable) {
            log(Log.WARN, TAG, "Could not resolve packages for uid=$uid: ${e.message}")
            null
        }
    }

    private fun isVendorIdHookEnabled(): Boolean {
        return try {
            getRemotePreferences("me.kavishdevar.librepods").getBoolean("vendor_id_hook", false)
        } catch (e: Throwable) {
            log(Log.WARN, TAG, "Could not read vendor_id_hook pref for Xiaomi compatibility hook: ${e.message}")
            false
        }
    }

    private fun looksLikeAirPods(device: BluetoothDevice?): Boolean {
        if (device == null) return false

        val address = try {
            device.address
        } catch (_: Throwable) {
            null
        }

        val savedAddress = try {
            getRemotePreferences("me.kavishdevar.librepods").getString("mac_address", "")
        } catch (_: Throwable) {
            ""
        }

        if (!savedAddress.isNullOrBlank() && address.equals(savedAddress, ignoreCase = true)) {
            return true
        }

        val name = try {
            device.name
        } catch (_: Throwable) {
            null
        }

        val alias = try {
            device.alias
        } catch (_: Throwable) {
            null
        }

        return name?.contains("AirPods", ignoreCase = true) == true ||
                alias?.contains("AirPods", ignoreCase = true) == true
    }

    private fun isNullOrZeroUuid(uuid: ParcelUuid?): Boolean {
        val value = uuid?.uuid ?: return true
        return value.mostSignificantBits == 0L && value.leastSignificantBits == 0L
    }

    private fun isXiaomiDevice(): Boolean {
        return android.os.Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
                android.os.Build.BRAND.equals("Xiaomi", ignoreCase = true)
    }

    private fun describeBluetoothDevice(device: BluetoothDevice?): String {
        if (device == null) return "null"

        val address = try {
            device.address
        } catch (_: Throwable) {
            "unknown"
        }

        val name = try {
            device.name
        } catch (_: Throwable) {
            null
        }

        return "${name ?: "unknown"}@$address"
    }

    private fun hookSettingsController(param: PackageLoadedParam, className: String) {
        log(Log.INFO, TAG, "Settings app detected, hooking Bluetooth icon handling")
        try {
            val headerControllerClass = Class.forName(className, false, param.defaultClassLoader)
            val updateIconMethod = headerControllerClass.getDeclaredMethod(
                "updateIcon",
                ImageView::class.java,
                String::class.java
            )

            hook(updateIconMethod).intercept { chain ->
                try {
                    log(Log.INFO, TAG, "Bluetooth icon hook called with args: ${chain.args.joinToString(", ")}")
                    val imageView = chain.args[0] as? ImageView
                    val iconUri = chain.args[1] as? String

                    if (imageView == null || iconUri == null) {
                        return@intercept chain.proceed()
                    }

                    val uri = iconUri.toUri()
                    if (!uri.toString().startsWith("android.resource://me.kavishdevar.librepods")) {
                        return@intercept chain.proceed()
                    }

                    log(Log.INFO, TAG, "Handling AirPods icon URI: $uri")

                    Handler(Looper.getMainLooper()).post {
                        try {
                            val context = imageView.context
                            val packageName = uri.authority ?: return@post
                            val packageContext = context.createPackageContext(
                                packageName,
                                Context.CONTEXT_IGNORE_SECURITY
                            )

                            val resPath = uri.pathSegments
                            if (resPath.size >= 2 && resPath[0] == "drawable") {
                                val resourceName = resPath[1]
                                val resourceId = packageContext.resources.getIdentifier(
                                    resourceName, "drawable", packageName
                                )

                                if (resourceId != 0) {
                                    val drawable = packageContext.resources.getDrawable(
                                        resourceId, packageContext.theme
                                    )
                                    imageView.setImageDrawable(drawable)
                                    imageView.alpha = 1.0f
                                    log(Log.INFO, TAG, "Successfully loaded icon from resource: $resourceName")
                                } else {
                                    log(Log.ERROR, TAG, "Resource not found: $resourceName")
                                }
                            }
                        } catch (e: Exception) {
                            log(Log.ERROR, TAG, "Error loading resource from URI $uri: ${e.message}")
                        }
                    }
                    null
                } catch (e: Exception) {
                    log(Log.ERROR, TAG, "Error in Bluetooth icon hook: ${e.message}")
                    chain.proceed()
                }
            }

            log(Log.INFO, TAG, "Successfully hooked updateIcon method in Bluetooth settings")
        } catch (e: Exception) {
            log(Log.ERROR, TAG, "Failed to hook Bluetooth icon handler: ${e.message}")
        }
    }
}


object NativeBridge {
    external fun setSdpHook(enabled: Boolean)
}
