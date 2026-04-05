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
private const val EXTERNAL_TEMP_GETTER = "getEXTERNALTEMPERATURE_C"
private const val EXTERNAL_TEMP_CALLBACK = "onEXTERNALTEMPERATURE_C"
private const val VEHICLE_SOURCE_LABEL = "Vehicle API"
private val TEMPERATURE_GETTER_NAMES = listOf(
    "getEXTERNALTEMPERATURE_C",
    "getOutdoorTemp",
    "outdoorTemp"
)
private val TEMPERATURE_FIELD_NAMES = listOf(
    "outdoorTemp",
    "externalTemperatureC",
    "externalTempC"
)

internal class VehicleTemperatureSource(private val context: Context) {
    private val mutableUpdates = MutableStateFlow<WeatherFetchResult?>(null)
    val updates: StateFlow<WeatherFetchResult?> = mutableUpdates.asStateFlow()
    var diagnosticMessage: String? = null
        private set

    @Volatile
    private var binding: TemperatureBinding? = null

    val hasActiveSubscription: Boolean
        get() = binding?.callbackRegistration != null

    fun start() {
        val activeBinding = ensureBinding() ?: return
        if (mutableUpdates.value == null) {
            val read = activeBinding.readCurrentTemperature()
            if (read.value != null) {
                publishTemperature(read.value, fromCallback = false)
            } else {
                recordDiagnostic(
                    read.errorMessage
                        ?: "A compatible manager was found, but getEXTERNALTEMPERATURE_C()/outdoorTemp returned no value."
                )
            }
        }
    }

    fun stop() {
        synchronized(this) {
            binding?.close()
            binding = null
        }
    }

    fun latestResult(): WeatherFetchResult? {
        mutableUpdates.value?.let { return it }
        val activeBinding = ensureBinding() ?: return null
        val read = activeBinding.readCurrentTemperature()
        if (read.value == null) {
            recordDiagnostic(
                read.errorMessage
                    ?: "A compatible manager was found, but getEXTERNALTEMPERATURE_C()/outdoorTemp returned no value."
            )
            return mutableUpdates.value
        }
        return publishTemperature(read.value, fromCallback = false)
    }

    private fun ensureBinding(): TemperatureBinding? = synchronized(this) {
        binding?.let { return it }
        val discovered = discoverBinding() ?: return null
        binding = discovered
        diagnosticMessage = null
        return discovered
    }

    private fun discoverBinding(): TemperatureBinding? {
        val attemptedTypes = linkedSetOf<String>()
        for (candidate in discoverCandidates()) {
            attemptedTypes += candidate.instance.javaClass.name
            val accessor = findExternalTemperatureAccessor(candidate.instance.javaClass) ?: continue
            val callbackRegistration = registerCallbackIfPossible(candidate.instance)
            AppLogger.log(
                TAG,
                "Using ${candidate.instance.javaClass.name} for ${accessor.debugName}" +
                    if (callbackRegistration != null) " with live subscription" else " with getter fallback"
            )
            return TemperatureBinding(
                manager = candidate.instance,
                accessor = accessor,
                callbackRegistration = callbackRegistration,
                releaseAction = candidate.releaseAction
            )
        }
        val attemptedSummary = attemptedTypes.take(4).joinToString()
        recordDiagnostic(
            if (attemptedSummary.isBlank()) {
                "No compatible car manager exposing getEXTERNALTEMPERATURE_C()/outdoorTemp was found."
            } else {
                "No compatible car manager exposing getEXTERNALTEMPERATURE_C()/outdoorTemp was found. Checked: $attemptedSummary"
            }
        )
        return null
    }

    private fun discoverCandidates(): List<ManagerCandidate> {
        val candidates = linkedMapOf<String, ManagerCandidate>()

        fun addCandidate(instance: Any?, releaseAction: (() -> Unit)? = null) {
            instance ?: return
            val key = instance.javaClass.name + "@" + System.identityHashCode(instance)
            candidates.putIfAbsent(key, ManagerCandidate(instance, releaseAction))
        }

        listOf(
            "car",
            "vehicle",
            "car_service",
            "vehicle_service",
            "car_manager",
            "vehicle_manager"
        ).forEach { serviceName ->
            addCandidate(runCatching { context.getSystemService(serviceName) }.getOrNull())
        }

        discoverContextServiceFields(::addCandidate)

        val carClass = runCatching { Class.forName("android.car.Car") }.getOrNull()
        val carInstance = createCarInstance(carClass)
        val carReleaseAction = carInstance?.let(::buildReleaseAction)

        addCandidate(carInstance, carReleaseAction)
        discoverNestedManagers(context, null, ::addCandidate)
        discoverNestedManagers(context.applicationContext, null, ::addCandidate)

        if (carClass != null && carInstance != null) {
            val getCarManager = allMethods(carClass).firstOrNull { method ->
                method.name == "getCarManager" &&
                    method.parameterCount == 1 &&
                    method.parameterTypes[0] == String::class.java
            }

            allFields(carClass)
                .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java && it.name.contains("SERVICE") }
                .forEach { field ->
                    val serviceName = runCatching { field.get(null) as? String }.getOrNull() ?: return@forEach
                    val manager = runCatching { getCarManager?.invoke(carInstance, serviceName) }.getOrNull()
                    addCandidate(manager, carReleaseAction)
                    discoverNestedManagers(manager, carReleaseAction, ::addCandidate)
                }

            allMethods(carInstance.javaClass)
                .filter { method ->
                    method.parameterCount == 0 &&
                        method.name.startsWith("get") &&
                        !method.returnType.isPrimitive &&
                        mayExposeExternalTemperature(method.returnType)
                }
                .forEach { method ->
                    val manager = runCatching { method.invoke(carInstance) }.getOrNull()
                    addCandidate(manager, carReleaseAction)
                    discoverNestedManagers(manager, carReleaseAction, ::addCandidate)
                }

            allFields(carInstance.javaClass)
                .filter { !Modifier.isStatic(it.modifiers) && mayExposeExternalTemperature(it.type) }
                .forEach { field ->
                    val manager = runCatching { field.get(carInstance) }.getOrNull()
                    addCandidate(manager, carReleaseAction)
                    discoverNestedManagers(manager, carReleaseAction, ::addCandidate)
                }
        }

        return candidates.values.toList()
    }

    private fun discoverContextServiceFields(addCandidate: (Any?, (() -> Unit)?) -> Unit) {
        allFields(Context::class.java)
            .filter { field ->
                Modifier.isStatic(field.modifiers) &&
                    field.type == String::class.java &&
                    field.name.endsWith("_SERVICE")
            }
            .mapNotNull { field -> runCatching { field.get(null) as? String }.getOrNull() }
            .distinct()
            .forEach { serviceName ->
                val service = runCatching { context.getSystemService(serviceName) }.getOrNull() ?: return@forEach
                val serviceNameMatches = serviceName.contains("car", ignoreCase = true) ||
                    serviceName.contains("vehicle", ignoreCase = true)
                if (serviceNameMatches || mayExposeExternalTemperature(service.javaClass)) {
                    addCandidate(service, null)
                    discoverNestedManagers(service, null, addCandidate)
                }
            }
    }

    private fun createCarInstance(carClass: Class<*>?): Any? {
        carClass ?: return null
        val createCar = allMethods(carClass).firstOrNull { method ->
            Modifier.isStatic(method.modifiers) &&
                method.name == "createCar" &&
                method.parameterTypes.firstOrNull() == Context::class.java
        } ?: return null
        val args = Array(createCar.parameterCount) { index ->
            when (createCar.parameterTypes[index]) {
                Context::class.java -> context
                else -> defaultParameterValue(createCar.parameterTypes[index])
            }
        }
        return runCatching { createCar.invoke(null, *args) }.getOrNull()
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

    private fun mayExposeExternalTemperature(type: Class<*>): Boolean {
        return type.simpleName.contains("Manager", ignoreCase = true) ||
            type.simpleName.contains("Vehicle", ignoreCase = true) ||
            type.simpleName.contains("Car", ignoreCase = true) ||
            findExternalTemperatureAccessor(type) != null
    }

    private fun findExternalTemperatureAccessor(type: Class<*>): TemperatureAccessor? {
        val exactMethod = allMethods(type).firstOrNull { candidate ->
            candidate.parameterCount == 0 &&
                returnsNumericValue(candidate.returnType) &&
                TEMPERATURE_GETTER_NAMES.any { alias ->
                    candidate.name == alias || candidate.name.equals(alias, ignoreCase = true)
                }
        }
        if (exactMethod != null) {
            return TemperatureAccessor.MethodAccessor(exactMethod)
        }

        val exactField = allFields(type).firstOrNull { candidate ->
            returnsNumericValue(candidate.type) &&
                TEMPERATURE_FIELD_NAMES.any { alias ->
                    candidate.name == alias || candidate.name.equals(alias, ignoreCase = true)
                }
        }
        if (exactField != null) {
            return TemperatureAccessor.FieldAccessor(exactField)
        }

        val looseMethod = allMethods(type).firstOrNull { candidate ->
            candidate.parameterCount == 0 &&
                returnsNumericValue(candidate.returnType) &&
                TEMPERATURE_GETTER_NAMES.any { alias ->
                    candidate.name.contains(alias, ignoreCase = true)
                }
        }
        return looseMethod?.let(TemperatureAccessor::MethodAccessor)
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

        var firstFailure: String? = null
        for (candidate in candidates) {
            val callback = createTemperatureCallback(candidate.listenerType)
            if (callback == null) {
                if (firstFailure == null && !candidate.listenerType.isInterface) {
                    firstFailure = "${candidate.listenerType.name} is not an interface listener."
                }
                continue
            }
            val args = buildInvocationArgs(candidate.method.parameterTypes, candidate.listenerIndex, callback)
            val registrationOutcome = runCatching { candidate.method.invoke(manager, *args) }
            if (registrationOutcome.isFailure) {
                if (firstFailure == null) {
                    firstFailure = "${candidate.method.name} failed: ${describeThrowable(registrationOutcome.exceptionOrNull())}"
                }
                continue
            }
            val releaseAction = buildUnregisterAction(
                manager = manager,
                registerMethod = candidate.method,
                listenerType = candidate.listenerType,
                callback = callback,
                registrationResult = registrationOutcome.getOrNull()
            )
            return CallbackRegistration(releaseAction)
        }
        if (firstFailure != null) {
            AppLogger.log(TAG, "Live subscription unavailable for ${manager.javaClass.name}: $firstFailure")
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
                    (args.firstOrNull() as? Number)?.toFloat()?.let { publishTemperature(it, fromCallback = true) }
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
            if (index == targetIndex) {
                targetValue
            } else {
                defaultParameterValue(parameterTypes[index])
            }
        }
    }

    private fun publishTemperature(value: Float, fromCallback: Boolean): WeatherFetchResult {
        diagnosticMessage = null
        val status = if (fromCallback) {
            "Vehicle temperature updated from callback."
        } else {
            "Using vehicle temperature."
        }
        val result = WeatherFetchResult(
            state = OverlayWeatherState(
                outsideTemperatureC = value,
                sourceLabel = VEHICLE_SOURCE_LABEL
            ),
            status = status,
            outdoorTemp = value,
            vehicleTemperatureDiagnostic = status
        )
        mutableUpdates.value = result
        return result
    }

    private fun returnsNumericValue(type: Class<*>): Boolean {
        return type == Float::class.java ||
            type == java.lang.Float.TYPE ||
            Number::class.java.isAssignableFrom(type)
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

    private fun allFields(type: Class<*>): List<java.lang.reflect.Field> {
        val fields = mutableListOf<java.lang.reflect.Field>()
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

    private fun discoverNestedManagers(
        owner: Any?,
        releaseAction: (() -> Unit)?,
        addCandidate: (Any?, (() -> Unit)?) -> Unit
    ) {
        owner ?: return
        allMethods(owner.javaClass)
            .filter { method ->
                method.parameterCount == 0 &&
                    !method.returnType.isPrimitive &&
                    !method.returnType.name.startsWith("java.") &&
                    (
                        mayExposeExternalTemperature(method.returnType) ||
                            method.name.contains("manager", ignoreCase = true) ||
                            method.name.contains("vehicle", ignoreCase = true) ||
                            method.name.contains("car", ignoreCase = true)
                        )
            }
            .forEach { method ->
                val nested = runCatching { method.invoke(owner) }.getOrNull()
                addCandidate(nested, releaseAction)
            }
    }

    private fun recordDiagnostic(message: String) {
        if (diagnosticMessage == message) return
        diagnosticMessage = message
        AppLogger.log(TAG, message)
    }

    private data class ManagerCandidate(
        val instance: Any,
        val releaseAction: (() -> Unit)?
    )

    private data class ListenerRegistrationCandidate(
        val method: Method,
        val listenerIndex: Int,
        val listenerType: Class<*>
    )

    private data class TemperatureBinding(
        val manager: Any,
        val accessor: TemperatureAccessor,
        val callbackRegistration: CallbackRegistration?,
        val releaseAction: (() -> Unit)?
    ) {
        fun readCurrentTemperature(): TemperatureReadResult {
            return runCatching { accessor.read(manager) }
                .fold(
                    onSuccess = { rawValue ->
                        val value = (rawValue as? Number)?.toFloat()?.takeUnless { it.isNaN() }
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

    private data class TemperatureReadResult(
        val value: Float? = null,
        val errorMessage: String? = null
    )

    private sealed interface TemperatureAccessor {
        val debugName: String
        fun read(target: Any): Any?

        data class MethodAccessor(private val method: Method) : TemperatureAccessor {
            override val debugName: String = "${method.name}()"
            override fun read(target: Any): Any? = method.invoke(target)
        }

        data class FieldAccessor(private val field: Field) : TemperatureAccessor {
            override val debugName: String = field.name
            override fun read(target: Any): Any? = field.get(target)
        }
    }

    private class CallbackRegistration(private val releaseAction: (() -> Unit)?) {
        fun close() {
            releaseAction?.invoke()
        }
    }
}

private fun describeThrowable(throwable: Throwable?): String {
    val root = generateSequence(throwable) { it.cause }.lastOrNull() ?: return "Unknown error"
    val message = root.message?.takeIf { it.isNotBlank() }
    return if (message != null) {
        "${root.javaClass.simpleName}: $message"
    } else {
        root.javaClass.simpleName
    }
}
