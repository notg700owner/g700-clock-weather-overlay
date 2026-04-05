package com.g700.clockweather.weather

import android.content.Context
import com.g700.clockweather.core.AppLogger
import com.g700.clockweather.overlay.OverlayWeatherState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

private const val TAG = "VehicleTempSource"
private const val EXTERNAL_TEMP_GETTER = "getEXTERNALTEMPERATURE_C"
private const val EXTERNAL_TEMP_CALLBACK = "onEXTERNALTEMPERATURE_C"
private const val VEHICLE_SOURCE_LABEL = "Vehicle API"

internal class VehicleTemperatureSource(private val context: Context) {
    private val mutableUpdates = MutableStateFlow<WeatherFetchResult?>(null)
    val updates: StateFlow<WeatherFetchResult?> = mutableUpdates.asStateFlow()

    @Volatile
    private var binding: TemperatureBinding? = null

    val hasActiveSubscription: Boolean
        get() = binding?.callbackRegistration != null

    fun start() {
        val activeBinding = ensureBinding() ?: return
        if (mutableUpdates.value == null) {
            activeBinding.readCurrentTemperature()?.let { publishTemperature(it, fromCallback = false) }
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
        val value = activeBinding.readCurrentTemperature() ?: return mutableUpdates.value
        return publishTemperature(value, fromCallback = false)
    }

    private fun ensureBinding(): TemperatureBinding? = synchronized(this) {
        binding?.let { return it }
        val discovered = discoverBinding() ?: return null
        binding = discovered
        return discovered
    }

    private fun discoverBinding(): TemperatureBinding? {
        for (candidate in discoverCandidates()) {
            val getter = findExternalTemperatureGetter(candidate.instance.javaClass) ?: continue
            val callbackRegistration = registerCallbackIfPossible(candidate.instance)
            AppLogger.log(
                TAG,
                "Using ${candidate.instance.javaClass.name} for $EXTERNAL_TEMP_GETTER" +
                    if (callbackRegistration != null) " with live subscription" else " with getter fallback"
            )
            return TemperatureBinding(
                manager = candidate.instance,
                getter = getter,
                callbackRegistration = callbackRegistration,
                releaseAction = candidate.releaseAction
            )
        }
        return null
    }

    private fun discoverCandidates(): List<ManagerCandidate> {
        val candidates = linkedMapOf<String, ManagerCandidate>()

        fun addCandidate(instance: Any?, releaseAction: (() -> Unit)? = null) {
            instance ?: return
            val key = instance.javaClass.name + "@" + System.identityHashCode(instance)
            candidates.putIfAbsent(key, ManagerCandidate(instance, releaseAction))
        }

        addCandidate(runCatching { context.getSystemService("car") }.getOrNull())

        val carClass = runCatching { Class.forName("android.car.Car") }.getOrNull()
        val carInstance = createCarInstance(carClass)
        val carReleaseAction = carInstance?.let(::buildReleaseAction)

        addCandidate(carInstance, carReleaseAction)

        if (carClass != null && carInstance != null) {
            val getCarManager = carClass.methods.firstOrNull { method ->
                method.name == "getCarManager" &&
                    method.parameterCount == 1 &&
                    method.parameterTypes[0] == String::class.java
            }

            carClass.fields
                .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java && it.name.contains("SERVICE") }
                .forEach { field ->
                    val serviceName = runCatching { field.get(null) as? String }.getOrNull() ?: return@forEach
                    val manager = runCatching { getCarManager?.invoke(carInstance, serviceName) }.getOrNull()
                    addCandidate(manager, carReleaseAction)
                }

            carInstance.javaClass.methods
                .filter { method ->
                    method.parameterCount == 0 &&
                        method.name.startsWith("get") &&
                        !method.returnType.isPrimitive &&
                        mayExposeExternalTemperature(method.returnType)
                }
                .forEach { method ->
                    val manager = runCatching { method.invoke(carInstance) }.getOrNull()
                    addCandidate(manager, carReleaseAction)
                }
        }

        return candidates.values.toList()
    }

    private fun createCarInstance(carClass: Class<*>?): Any? {
        carClass ?: return null
        val createCar = carClass.methods.firstOrNull { method ->
            Modifier.isStatic(method.modifiers) &&
                method.name == "createCar" &&
                method.parameterCount == 1 &&
                method.parameterTypes[0] == Context::class.java
        } ?: return null
        return runCatching { createCar.invoke(null, context) }.getOrNull()
    }

    private fun buildReleaseAction(owner: Any): (() -> Unit)? {
        val method = owner.javaClass.methods.firstOrNull { candidate ->
            candidate.parameterCount == 0 && (
                candidate.name == "disconnect" ||
                    candidate.name == "disconnectFromCarService" ||
                    candidate.name == "close"
                )
        } ?: return null
        return { runCatching { method.invoke(owner) } }
    }

    private fun mayExposeExternalTemperature(type: Class<*>): Boolean {
        return type.simpleName.contains("Manager", ignoreCase = true) || findExternalTemperatureGetter(type) != null
    }

    private fun findExternalTemperatureGetter(type: Class<*>): Method? {
        val method = type.methods.firstOrNull { candidate ->
            candidate.name == EXTERNAL_TEMP_GETTER &&
                candidate.parameterCount == 0 &&
                returnsNumericValue(candidate.returnType)
        } ?: return null
        method.isAccessible = true
        return method
    }

    private fun registerCallbackIfPossible(manager: Any): CallbackRegistration? {
        val candidates = manager.javaClass.methods
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
            val registrationOutcome = runCatching { candidate.method.invoke(manager, *args) }
            if (registrationOutcome.isFailure) continue
            val releaseAction = buildUnregisterAction(
                manager = manager,
                registerMethod = candidate.method,
                listenerType = candidate.listenerType,
                callback = callback,
                registrationResult = registrationOutcome.getOrNull()
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

        val unregisterMethod = manager.javaClass.methods.firstOrNull { method ->
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
        return type.isInterface && type.methods.any { method ->
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
        val result = WeatherFetchResult(
            state = OverlayWeatherState(
                outsideTemperatureC = value,
                sourceLabel = VEHICLE_SOURCE_LABEL
            ),
            status = if (fromCallback) {
                "Vehicle temperature updated from callback."
            } else {
                "Using vehicle temperature."
            }
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
        val getter: Method,
        val callbackRegistration: CallbackRegistration?,
        val releaseAction: (() -> Unit)?
    ) {
        fun readCurrentTemperature(): Float? {
            return runCatching { getter.invoke(manager) as? Number }
                .getOrNull()
                ?.toFloat()
                ?.takeUnless { it.isNaN() }
        }

        fun close() {
            callbackRegistration?.close()
            releaseAction?.invoke()
        }
    }

    private class CallbackRegistration(private val releaseAction: (() -> Unit)?) {
        fun close() {
            releaseAction?.invoke()
        }
    }
}
