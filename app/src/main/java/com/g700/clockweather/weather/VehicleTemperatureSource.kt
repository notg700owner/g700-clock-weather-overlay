package com.g700.clockweather.weather

import android.content.Context
import com.g700.clockweather.core.AppLogger
import com.g700.clockweather.overlay.OverlayWeatherState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

private const val TAG = "VehicleTempSource"
private const val EXTERNAL_TEMP_CALLBACK = "onEXTERNALTEMPERATURE_C"
private const val VEHICLE_SOURCE_LABEL = "Vehicle API"
private const val AUTOLINK_RETRY_ATTEMPTS = 8
private const val AUTOLINK_RETRY_DELAY_MS = 250L
private const val OUTSIDE_TEMPERATURE_PROPERTY_ID = 0x11600305.toInt()
private val DIRECT_TEMPERATURE_GETTER_NAMES = listOf(
    AutolinkMethodCatalog.OUTDOOR_TEMP_GETTER,
    "getOutdoorTemp",
    "outdoorTemp"
)
private val DIRECT_TEMPERATURE_FIELD_NAMES = listOf(
    "outdoorTemp",
    "externalTemperatureC",
    "externalTempC"
)

internal class VehicleTemperatureSource(private val context: Context) {
    private val mutableUpdates = MutableStateFlow<WeatherFetchResult?>(null)
    val updates: StateFlow<WeatherFetchResult?> = mutableUpdates.asStateFlow()

    var diagnosticMessage: String? = null
        private set

    private val bindingLock = Any()
    private var autolinkBinding: DirectTemperatureBinding? = null
    private var androidCarBinding: AndroidCarTemperatureBinding? = null
    private var autolinkDiscoveryAttempted = false
    private var androidCarDiscoveryAttempted = false

    val hasActiveSubscription: Boolean
        get() = synchronized(bindingLock) {
            autolinkBinding?.callbackRegistration != null
        }

    fun start() {
        ensureAutolinkBinding()
    }

    fun stop() {
        synchronized(bindingLock) {
            autolinkBinding?.close()
            androidCarBinding?.close()
            autolinkBinding = null
            androidCarBinding = null
            autolinkDiscoveryAttempted = false
            androidCarDiscoveryAttempted = false
        }
    }

    fun latestResult(): WeatherFetchResult? {
        val autolink = ensureAutolinkBinding()
        val androidCar = ensureAndroidCarBinding()

        if (autolink == null && androidCar == null) {
            recordDiagnostic("No vehicle temperature source was found. Expected Autolink car manager or Android Car property manager.")
            return mutableUpdates.value
        }

        val reads = mutableListOf<SourceRead>()
        if (autolink != null) {
            reads += readAutolinkTemperature(autolink)
        }
        if (androidCar != null) {
            reads += androidCar.readCurrentTemperature()
        }

        selectBestRead(reads)?.let { chosen ->
            val diagnostic = when {
                chosen.source == SourceKind.ANDROID_CAR && reads.any { it.source == SourceKind.AUTOLINK && it.isPlaceholder } -> {
                    "Autolink returned a placeholder value; using Android Car outside temperature."
                }
                else -> chosen.diagnostic
            }
            chosen.value?.let { return publishTemperature(it, diagnostic) }
        }

        val message = reads.mapNotNull { it.errorMessage }.joinToString(" | ")
            .ifBlank { "Vehicle temperature is unavailable." }
        recordDiagnostic(message)
        return mutableUpdates.value
    }

    private fun ensureAutolinkBinding(): DirectTemperatureBinding? = synchronized(bindingLock) {
        if (autolinkDiscoveryAttempted) return autolinkBinding
        autolinkDiscoveryAttempted = true
        autolinkBinding = discoverAutolinkBinding()
        autolinkBinding
    }

    private fun ensureAndroidCarBinding(): AndroidCarTemperatureBinding? = synchronized(bindingLock) {
        if (androidCarDiscoveryAttempted) return androidCarBinding
        androidCarDiscoveryAttempted = true
        androidCarBinding = discoverAndroidCarBinding()
        androidCarBinding
    }

    private fun discoverAutolinkBinding(): DirectTemperatureBinding? {
        val apiClass = runCatching { Class.forName(AutolinkMethodCatalog.API_CLASS) }.getOrNull()
            ?: return null
        runCatching { Class.forName(AutolinkMethodCatalog.CAR_MANAGER_CLASS) }
        val createApi = allMethods(apiClass).firstOrNull { method ->
            Modifier.isStatic(method.modifiers) &&
                method.name == "createApi" &&
                method.parameterCount == 1 &&
                method.parameterTypes[0] == Context::class.java
        } ?: return null

        val apiInstance = runCatching { createApi.invoke(null, context.applicationContext) }
            .getOrElse { error ->
                AppLogger.log(TAG, "Autolink Api.createApi failed", error)
                return null
            } ?: return null

        val getManager = allMethods(apiInstance.javaClass).firstOrNull { method ->
            method.name == "getManager" &&
                method.parameterCount == 1 &&
                method.parameterTypes[0] == String::class.java
        } ?: return null

        val manager = runCatching { getManager.invoke(apiInstance, "car") }
            .getOrElse { error ->
                AppLogger.log(TAG, "Autolink Api.getManager(\"car\") failed", error)
                return null
            } ?: return null

        val accessor = findDirectTemperatureAccessor(manager.javaClass) ?: return null
        val callbackRegistration = registerCallbackIfPossible(manager)
        val releaseAction = combineReleaseActions(
            buildReleaseAction(manager),
            buildReleaseAction(apiInstance)
        )

        AppLogger.log(
            TAG,
            "Autolink manager ready: ${manager.javaClass.name} using ${accessor.debugName}" +
                if (callbackRegistration != null) " with live subscription" else ""
        )

        return DirectTemperatureBinding(
            manager = manager,
            accessor = accessor,
            callbackRegistration = callbackRegistration,
            releaseAction = releaseAction
        )
    }

    private fun discoverAndroidCarBinding(): AndroidCarTemperatureBinding? {
        val carClass = runCatching { Class.forName("android.car.Car") }.getOrNull() ?: return null
        val createCar = allMethods(carClass).firstOrNull { method ->
            Modifier.isStatic(method.modifiers) &&
                method.name == "createCar" &&
                method.parameterTypes.firstOrNull() == Context::class.java
        } ?: return null

        val args = Array(createCar.parameterCount) { index ->
            when (createCar.parameterTypes[index]) {
                Context::class.java -> context.applicationContext
                else -> defaultParameterValue(createCar.parameterTypes[index])
            }
        }

        val carInstance = runCatching { createCar.invoke(null, *args) }
            .getOrElse { error ->
                AppLogger.log(TAG, "android.car.Car.createCar failed", error)
                return null
            } ?: return null

        waitForAndroidCarConnection(carInstance)

        val getCarManager = allMethods(carInstance.javaClass).firstOrNull { method ->
            method.name == "getCarManager" &&
                method.parameterCount == 1 &&
                method.parameterTypes[0] == String::class.java
        } ?: return null

        val propertyManager = runCatching { getCarManager.invoke(carInstance, "property") }
            .getOrElse { error ->
                AppLogger.log(TAG, "android.car.Car.getCarManager(\"property\") failed", error)
                return null
            } ?: return null

        val releaseAction = buildReleaseAction(carInstance)
        val targets = discoverPropertyTargets(propertyManager).ifEmpty {
            listOf(
                CarPropertyTarget(
                    propertyId = OUTSIDE_TEMPERATURE_PROPERTY_ID,
                    areaId = 0,
                    label = "ENV_OUTSIDE_TEMPERATURE",
                    typeName = "Float"
                )
            )
        }

        AppLogger.log(
            TAG,
            "Android Car property manager ready: ${propertyManager.javaClass.name} with ${targets.size} outside-temperature target(s)"
        )

        return AndroidCarTemperatureBinding(
            propertyManager = propertyManager,
            targets = targets,
            releaseAction = releaseAction
        )
    }

    private fun readAutolinkTemperature(binding: DirectTemperatureBinding): SourceRead {
        var lastRead = binding.readCurrentTemperature()
        repeat(AUTOLINK_RETRY_ATTEMPTS - 1) {
            val value = lastRead.value
            if (value != null && !looksLikeAutolinkPlaceholder(value)) return@repeat
            Thread.sleep(AUTOLINK_RETRY_DELAY_MS)
            lastRead = binding.readCurrentTemperature()
        }

        val value = lastRead.value
        if (value != null) {
            return SourceRead(
                source = SourceKind.AUTOLINK,
                value = value,
                diagnostic = "Using Autolink exterior temperature.",
                errorMessage = if (looksLikeAutolinkPlaceholder(value)) {
                    "Autolink returned placeholder temperature ${formatTemp(value)}."
                } else {
                    null
                },
                isPlaceholder = looksLikeAutolinkPlaceholder(value)
            )
        }

        return SourceRead(
            source = SourceKind.AUTOLINK,
            errorMessage = lastRead.errorMessage ?: "Autolink exterior temperature is unavailable.",
            diagnostic = "Autolink exterior temperature is unavailable."
        )
    }

    private fun selectBestRead(reads: List<SourceRead>): SourceRead? {
        val autolink = reads.firstOrNull { it.source == SourceKind.AUTOLINK && it.value != null }
        val androidCar = reads.firstOrNull { it.source == SourceKind.ANDROID_CAR && it.value != null }

        return when {
            autolink?.value != null && !autolink.isPlaceholder -> autolink
            androidCar?.value != null -> androidCar
            autolink?.value != null -> autolink
            else -> reads.firstOrNull { it.value != null }
        }
    }

    private fun onAutolinkCallbackTemperature(value: Float) {
        if (looksLikeAutolinkPlaceholder(value)) {
            val fallbackValue = ensureAndroidCarBinding()
                ?.readCurrentTemperature()
                ?.takeUnless { it.value == null }
            if (fallbackValue?.value != null) {
                publishTemperature(
                    fallbackValue.value,
                    "Autolink callback returned a placeholder value; using Android Car outside temperature."
                )
                return
            }
        }

        publishTemperature(value, "Vehicle temperature updated from Autolink callback.")
    }

    private fun waitForAndroidCarConnection(carInstance: Any) {
        val isConnected = allMethods(carInstance.javaClass).firstOrNull {
            it.name == "isConnected" && it.parameterCount == 0
        }
        val isConnecting = allMethods(carInstance.javaClass).firstOrNull {
            it.name == "isConnecting" && it.parameterCount == 0
        }
        val connect = allMethods(carInstance.javaClass).firstOrNull {
            it.name == "connect" && it.parameterCount == 0
        }

        val connectedNow = runCatching { isConnected?.invoke(carInstance) as? Boolean }.getOrNull() ?: false
        val connectingNow = runCatching { isConnecting?.invoke(carInstance) as? Boolean }.getOrNull() ?: false
        if (!connectedNow && !connectingNow) {
            runCatching { connect?.invoke(carInstance) }
        }

        repeat(12) {
            val ready = runCatching { isConnected?.invoke(carInstance) as? Boolean }.getOrNull() ?: false
            if (ready) return
            Thread.sleep(250L)
        }
    }

    private fun discoverPropertyTargets(propertyManager: Any): List<CarPropertyTarget> {
        val getPropertyList = allMethods(propertyManager.javaClass).firstOrNull {
            it.name == "getPropertyList" && it.parameterCount == 0
        } ?: return emptyList()

        val configs = runCatching { getPropertyList.invoke(propertyManager) as? List<*> }.getOrNull().orEmpty()
        val targets = mutableListOf<CarPropertyTarget>()
        configs.filterNotNull().forEach { config ->
            val propertyId = runCatching { invokeZeroArg(config, "getPropertyId") }.getOrNull()?.toIntValue() ?: return@forEach
            val label = resolveAndroidCarPropertyName(propertyId)
            val matches = propertyId == OUTSIDE_TEMPERATURE_PROPERTY_ID ||
                label.contains("OUTSIDE", ignoreCase = true) ||
                label.contains("EXTERNAL", ignoreCase = true) ||
                label.contains("AMBIENT", ignoreCase = true)
            if (!matches) return@forEach

            val typeName = runCatching { invokeZeroArg(config, "getPropertyType") }.getOrNull()?.toString()
            val areaIds = runCatching { invokeZeroArg(config, "getAreaIds") }.getOrNull()
            val areas = when (areaIds) {
                is IntArray -> areaIds.toList()
                is Array<*> -> areaIds.mapNotNull { it.toIntValue() }
                else -> listOf(0)
            }.ifEmpty { listOf(0) }

            areas.forEach { areaId ->
                targets += CarPropertyTarget(
                    propertyId = propertyId,
                    areaId = areaId,
                    label = label,
                    typeName = typeName
                )
            }
        }
        return targets
    }

    private fun findDirectTemperatureAccessor(type: Class<*>): DirectTemperatureAccessor? {
        val method = allMethods(type).firstOrNull { candidate ->
            candidate.parameterCount == 0 &&
                returnsNumericValue(candidate.returnType) &&
                DIRECT_TEMPERATURE_GETTER_NAMES.any { alias ->
                    candidate.name == alias || candidate.name.equals(alias, ignoreCase = true)
                }
        }
        if (method != null) {
            return DirectTemperatureAccessor.MethodAccessor(method)
        }

        val field = allFields(type).firstOrNull { candidate ->
            returnsNumericValue(candidate.type) &&
                DIRECT_TEMPERATURE_FIELD_NAMES.any { alias ->
                    candidate.name == alias || candidate.name.equals(alias, ignoreCase = true)
                }
        }
        if (field != null) {
            return DirectTemperatureAccessor.FieldAccessor(field)
        }

        return null
    }

    private fun registerCallbackIfPossible(manager: Any): CallbackRegistration? {
        val candidates = allMethods(manager.javaClass)
            .mapNotNull { method ->
                val listenerIndex = method.parameterTypes.indexOfFirst(::isExternalTemperatureListenerType)
                if (listenerIndex == -1) {
                    null
                } else {
                    ListenerRegistrationCandidate(method, listenerIndex, method.parameterTypes[listenerIndex])
                }
            }
            .sortedBy { registrationPriority(it.method.name) }

        for (candidate in candidates) {
            val callback = createTemperatureCallback(candidate.listenerType) ?: continue
            val args = buildInvocationArgs(candidate.method.parameterTypes, candidate.listenerIndex, callback)
            val registration = runCatching { candidate.method.invoke(manager, *args) }.getOrNull() ?: continue
            val releaseAction = buildUnregisterAction(
                manager = manager,
                registerMethod = candidate.method,
                listenerType = candidate.listenerType,
                callback = callback,
                registrationResult = registration
            )
            return CallbackRegistration(releaseAction)
        }
        return null
    }

    private fun buildUnregisterAction(
        manager: Any,
        registerMethod: Method,
        listenerType: Class<*>,
        callback: Any,
        registrationResult: Any?
    ): (() -> Unit)? {
        (registrationResult as? AutoCloseable)?.let { closeable ->
            return { runCatching { closeable.close() } }
        }

        val returnedClose = registrationResult?.javaClass?.methods?.firstOrNull {
            it.parameterCount == 0 && (it.name == "close" || it.name == "dispose" || it.name == "cancel")
        }
        if (returnedClose != null) {
            return { runCatching { returnedClose.invoke(registrationResult) } }
        }

        val unregisterMethod = allMethods(manager.javaClass).firstOrNull { method ->
            val name = method.name.lowercase()
            (name.contains("unregister") || name.contains("remove")) &&
                method.parameterTypes.indexOfFirst { listenerType.isAssignableFrom(it) } != -1
        }
        if (unregisterMethod != null) {
            val unregisterIndex = unregisterMethod.parameterTypes.indexOfFirst { listenerType.isAssignableFrom(it) }
            return {
                runCatching {
                    unregisterMethod.invoke(
                        manager,
                        *buildInvocationArgs(unregisterMethod.parameterTypes, unregisterIndex, callback)
                    )
                }
            }
        }

        return if (registerMethod.name.startsWith("set") && registerMethod.parameterCount == 1) {
            { runCatching { registerMethod.invoke(manager, null) } }
        } else {
            null
        }
    }

    private fun createTemperatureCallback(listenerType: Class<*>): Any? {
        if (!listenerType.isInterface) return null

        lateinit var proxy: Any
        proxy = Proxy.newProxyInstance(
            listenerType.classLoader,
            arrayOf(listenerType)
        ) { _, method, args ->
            when {
                method.name == EXTERNAL_TEMP_CALLBACK && args?.size == 1 -> {
                    (args.firstOrNull() as? Number)?.toFloat()?.let(::onAutolinkCallbackTemperature)
                    defaultReturnValue(method.returnType)
                }
                method.name == "toString" && method.parameterCount == 0 -> "ExternalTemperatureCallbackProxy"
                method.name == "hashCode" && method.parameterCount == 0 -> System.identityHashCode(proxy)
                method.name == "equals" && method.parameterCount == 1 -> proxy === args?.firstOrNull()
                else -> defaultReturnValue(method.returnType)
            }
        }
        return proxy
    }

    private fun isExternalTemperatureListenerType(type: Class<*>): Boolean {
        return type.isInterface && allMethods(type).any { method ->
            method.name == EXTERNAL_TEMP_CALLBACK &&
                method.parameterCount == 1 &&
                returnsNumericValue(method.parameterTypes[0])
        }
    }

    private fun registrationPriority(methodName: String): Int {
        val name = methodName.lowercase()
        return when {
            name.contains("register") -> 0
            name.contains("subscribe") -> 1
            name.contains("add") -> 2
            name.contains("set") -> 3
            else -> 4
        }
    }

    private fun buildInvocationArgs(parameterTypes: Array<Class<*>>, targetIndex: Int, targetValue: Any): Array<Any?> {
        return Array(parameterTypes.size) { index ->
            if (index == targetIndex) targetValue else defaultParameterValue(parameterTypes[index])
        }
    }

    private fun publishTemperature(value: Float, diagnostic: String): WeatherFetchResult {
        diagnosticMessage = null
        AppLogger.log(TAG, "$diagnostic (${formatTemp(value)})")
        val result = WeatherFetchResult(
            state = OverlayWeatherState(
                outsideTemperatureC = value,
                sourceLabel = VEHICLE_SOURCE_LABEL
            ),
            status = "Using vehicle temperature.",
            outdoorTemp = value,
            vehicleTemperatureDiagnostic = diagnostic
        )
        mutableUpdates.value = result
        return result
    }

    private fun looksLikeAutolinkPlaceholder(value: Float): Boolean {
        return value == -1f || value.isNaN()
    }

    private fun resolveAndroidCarPropertyName(propertyId: Int): String {
        return when (propertyId) {
            OUTSIDE_TEMPERATURE_PROPERTY_ID -> "ENV_OUTSIDE_TEMPERATURE"
            else -> "PROPERTY_0x${propertyId.toUInt().toString(16)}"
        }
    }

    private fun invokeZeroArg(target: Any, methodName: String): Any? {
        val method = allMethods(target.javaClass).firstOrNull {
            it.name == methodName && it.parameterCount == 0
        } ?: return null
        return method.invoke(target)
    }

    private fun unwrapCarPropertyValue(value: Any?): Any? {
        value ?: return null
        val method = allMethods(value.javaClass).firstOrNull {
            it.name == "getValue" && it.parameterCount == 0
        } ?: return value
        return runCatching { method.invoke(value) }.getOrNull() ?: value
    }

    private fun readAndroidCarProperty(propertyManager: Any, target: CarPropertyTarget): Float? {
        val directValue = runCatching {
            when {
                target.typeName?.contains("Float") == true || target.typeName?.contains("float") == true -> {
                    invokeMethod(propertyManager, "getFloatProperty", target.propertyId, target.areaId)
                }
                target.typeName?.contains("Integer") == true || target.typeName?.contains("int") == true -> {
                    invokeMethod(propertyManager, "getIntProperty", target.propertyId, target.areaId)
                }
                target.typeName?.contains("Boolean") == true || target.typeName?.contains("boolean") == true -> {
                    invokeMethod(propertyManager, "getBooleanProperty", target.propertyId, target.areaId)
                }
                else -> invokeMethod(propertyManager, "getProperty", target.propertyId, target.areaId)
            }
        }.recoverCatching {
            invokeMethod(propertyManager, "getProperty", target.propertyId, target.areaId)
        }.getOrNull()

        return unwrapCarPropertyValue(directValue).toFloatValue()
    }

    private fun invokeMethod(target: Any, methodName: String, vararg args: Any?): Any? {
        val method = allMethods(target.javaClass).firstOrNull { candidate ->
            candidate.name == methodName && candidate.parameterCount == args.size
        } ?: return null
        return method.invoke(target, *args)
    }

    private fun returnsNumericValue(type: Class<*>): Boolean {
        return type == Float::class.java ||
            type == java.lang.Float.TYPE ||
            Number::class.java.isAssignableFrom(type)
    }

    private fun buildReleaseAction(owner: Any): (() -> Unit)? {
        val method = allMethods(owner.javaClass).firstOrNull { candidate ->
            candidate.parameterCount == 0 && (
                candidate.name == "disconnect" ||
                    candidate.name == "disconnectFromCarService" ||
                    candidate.name == "close"
                )
        } ?: return null
        return { runCatching { method.invoke(owner) } }
    }

    private fun combineReleaseActions(vararg actions: (() -> Unit)?): (() -> Unit)? {
        val validActions = actions.filterNotNull()
        if (validActions.isEmpty()) return null
        return { validActions.forEach { runCatching { it.invoke() } } }
    }

    private fun defaultParameterValue(type: Class<*>): Any? {
        return when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Character.TYPE -> '\u0000'
            else -> null
        }
    }

    private fun defaultReturnValue(type: Class<*>): Any? = defaultParameterValue(type)

    private fun allMethods(type: Class<*>): List<Method> {
        val methods = mutableListOf<Method>()
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            current.declaredMethods.forEach { method ->
                runCatching { method.isAccessible = true }
                methods += method
            }
            current = current.superclass
        }
        return methods.distinctBy { method ->
            method.name + method.parameterTypes.joinToString(separator = ",") { it.name } + method.returnType.name
        }
    }

    private fun allFields(type: Class<*>): List<Field> {
        val fields = mutableListOf<Field>()
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            current.declaredFields.forEach { field ->
                runCatching { field.isAccessible = true }
                fields += field
            }
            current = current.superclass
        }
        return fields.distinctBy { it.name + it.type.name }
    }

    private fun recordDiagnostic(message: String) {
        if (diagnosticMessage == message) return
        diagnosticMessage = message
        AppLogger.log(TAG, message)
    }

    private data class DirectTemperatureBinding(
        val manager: Any,
        val accessor: DirectTemperatureAccessor,
        val callbackRegistration: CallbackRegistration?,
        val releaseAction: (() -> Unit)?
    ) {
        fun readCurrentTemperature(): TemperatureReadResult {
            return runCatching { accessor.read(manager) }
                .fold(
                    onSuccess = { rawValue ->
                        val value = rawValue.toFloatValue()?.takeUnless { it.isNaN() }
                        if (value != null) {
                            TemperatureReadResult(value = value)
                        } else {
                            TemperatureReadResult(
                                errorMessage = "${manager.javaClass.name}.${accessor.debugName} returned ${rawValue?.javaClass?.name ?: "null"}."
                            )
                        }
                    },
                    onFailure = { error ->
                        TemperatureReadResult(
                            errorMessage = "${manager.javaClass.name}.${accessor.debugName} failed: ${describeThrowable(error)}"
                        )
                    }
                )
        }

        fun close() {
            callbackRegistration?.close()
            releaseAction?.invoke()
        }
    }

    private inner class AndroidCarTemperatureBinding(
        val propertyManager: Any,
        val targets: List<CarPropertyTarget>,
        val releaseAction: (() -> Unit)?
    ) {
        fun readCurrentTemperature(): SourceRead {
            val errors = mutableListOf<String>()
            targets.forEach { target ->
                val value = runCatching { readAndroidCarProperty(propertyManager, target) }
                    .getOrElse { error ->
                        errors += "${target.label}[area=${target.areaId}] failed: ${describeThrowable(error)}"
                        null
                    }
                if (value != null) {
                    return SourceRead(
                        source = SourceKind.ANDROID_CAR,
                        value = value,
                        diagnostic = "Using Android Car outside temperature (${target.label})."
                    )
                }
            }
            return SourceRead(
                source = SourceKind.ANDROID_CAR,
                errorMessage = errors.joinToString(" | ").ifBlank { "Android Car outside temperature is unavailable." },
                diagnostic = "Android Car outside temperature is unavailable."
            )
        }

        fun close() {
            releaseAction?.invoke()
        }
    }

    private sealed interface DirectTemperatureAccessor {
        val debugName: String
        fun read(target: Any): Any?

        data class MethodAccessor(private val method: Method) : DirectTemperatureAccessor {
            override val debugName: String = "${method.name}()"
            override fun read(target: Any): Any? = method.invoke(target)
        }

        data class FieldAccessor(private val field: Field) : DirectTemperatureAccessor {
            override val debugName: String = field.name
            override fun read(target: Any): Any? = field.get(target)
        }
    }

    private data class CarPropertyTarget(
        val propertyId: Int,
        val areaId: Int,
        val label: String,
        val typeName: String?
    )

    private data class SourceRead(
        val source: SourceKind,
        val value: Float? = null,
        val errorMessage: String? = null,
        val diagnostic: String,
        val isPlaceholder: Boolean = false
    )

    private data class ListenerRegistrationCandidate(
        val method: Method,
        val listenerIndex: Int,
        val listenerType: Class<*>
    )

    private data class TemperatureReadResult(
        val value: Float? = null,
        val errorMessage: String? = null
    )

    private class CallbackRegistration(private val releaseAction: (() -> Unit)?) {
        fun close() {
            releaseAction?.invoke()
        }
    }

    private enum class SourceKind {
        AUTOLINK,
        ANDROID_CAR
    }
}

private fun Any?.toFloatValue(): Float? {
    return when (this) {
        is Float -> this
        is Double -> this.toFloat()
        is Int -> this.toFloat()
        is Long -> this.toFloat()
        is Short -> this.toFloat()
        is Byte -> this.toFloat()
        is String -> this.toFloatOrNull()
        else -> null
    }
}

private fun Any?.toIntValue(): Int? {
    return when (this) {
        is Int -> this
        is Long -> this.toInt()
        is Short -> this.toInt()
        is Byte -> this.toInt()
        is String -> this.toIntOrNull()
        else -> null
    }
}

private fun formatTemp(value: Float): String = "%.1fC".format(value)

private fun describeThrowable(throwable: Throwable?): String {
    val root = generateSequence(throwable) { it.cause }.lastOrNull() ?: return "Unknown error"
    val message = root.message?.takeIf { it.isNotBlank() }
    return if (message != null) {
        "${root.javaClass.simpleName}: $message"
    } else {
        root.javaClass.simpleName
    }
}
