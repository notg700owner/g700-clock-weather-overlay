package com.g700.automation.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ServiceRuntimeBus {
    private val mutableState = MutableStateFlow(ServiceRuntimeState())
    val state: StateFlow<ServiceRuntimeState> = mutableState.asStateFlow()

    fun set(value: ServiceRuntimeState) {
        mutableState.value = value
    }

    fun update(transform: (ServiceRuntimeState) -> ServiceRuntimeState) {
        mutableState.value = transform(mutableState.value)
    }

    fun reset() {
        mutableState.value = ServiceRuntimeState()
    }
}
