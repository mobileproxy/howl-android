package io.nekohasekai.sfa.health

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** The manifest keeps local UI and service in one process; remote sessions never use this evidence. */
object HealthRuntime {
    val monitor = HealthMonitor(CoroutineScope(SupervisorJob() + Dispatchers.Default), SystemClock::elapsedRealtime)
    val state = monitor.state
    fun view(): HealthSnapshot = monitor.view()
    fun refresh() = monitor.refresh()
    fun stop() = monitor.stop()
}
