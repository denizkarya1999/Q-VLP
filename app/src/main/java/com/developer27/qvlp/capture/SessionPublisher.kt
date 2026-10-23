package com.developer27.qvlp.capture

import android.content.Context
import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.io.File
import java.util.concurrent.Executors

/** Automatically publishes each finished position without blocking the camera or requiring a Save action. */
internal object SessionPublisher {
    data class State(val running: Boolean = false,
                     val message: String = "Auto-save · Pictures/Q-VLP",
                     val needsAttention: Boolean = false)
    private data class Job(val context: Application, val snapshot: SessionSnapshot, val positionId: Int?)
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { action -> Thread({
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        action.run()
    }, "qvlp-public-saves") }
    private val pending = linkedMapOf<String, Job>()
    private val observers = mutableSetOf<(State) -> Unit>()
    private var current: Job? = null
    private var control: CopyControl? = null
    private var draining = false
    private var selectingFolder = false
    private var scanQueued = false
    private var state = State()

    fun observe(observer: (State) -> Unit): () -> Unit {
        synchronized(lock) { observers.add(observer) }
        notifyObservers()
        return { synchronized(lock) { observers.remove(observer) }; Unit }
    }

    private fun notifyObservers() = main.post {
        val (value, listeners) = synchronized(lock) { state to observers.toList() }
        listeners.forEach { it(value) }
    }

    private fun update(value: State) {
        synchronized(lock) { state = value }
        notifyObservers()
    }

    private fun enableAutoSave(context: Context) {
        // Upgrade earlier versions that allowed a persistent pause. Every position now auto-saves.
        val prefs = context.getSharedPreferences("public_session_storage", Context.MODE_PRIVATE)
        if (prefs.contains("paused")) prefs.edit().remove("paused").apply()
    }

    /** Called on the capture I/O queue only after the attempt and metadata are closed. */
    fun captureReady(context: Context, session: File, positionId: Int) {
        enableAutoSave(context)
        runCatching { enqueue(context, PublicSessionStorage.snapshot(session), positionId) }.onFailure {
            update(State(message = "Could not prepare auto-save · Tap to retry", needsAttention = true))
        }
    }

    /** Recover unfinished saves on reopening. Already saved sessions are left alone. */
    fun request(context: Context) {
        val app = context.applicationContext
        enableAutoSave(app)
        if (!PublicSessionStorage.configured(app)) {
            update(State(message = "Set up auto-save · Choose Pictures/Q-VLP", needsAttention = true))
            return
        }
        synchronized(lock) {
            if (scanQueued || selectingFolder) return
            scanQueued = true
        }
        SessionFiles.io.execute {
            try {
                SessionFiles.directories(app).forEach { session ->
                    val snapshot = PublicSessionStorage.snapshot(session)
                    if (PublicSessionStorage.needsSave(app, snapshot)) enqueue(app, snapshot)
                }
                synchronized(lock) {
                    if (!draining && !selectingFolder) {
                        if (PublicSessionStorage.error(app) != null) update(failedState())
                        else if (state.needsAttention) update(State())
                    }
                }
            } catch (error: Exception) {
                update(State(message = "Could not prepare auto-save · Tap to retry", needsAttention = true))
            } finally {
                synchronized(lock) { scanQueued = false }
            }
        }
    }

    fun selectFolder(context: Context, uri: Uri, completed: (Result<Unit>) -> Unit) {
        val app = context.applicationContext
        synchronized(lock) {
            selectingFolder = true
            pending.clear()
            control?.cancel()
        }
        worker.execute {
            val result = runCatching { PublicSessionStorage.select(app, uri) }
            synchronized(lock) { selectingFolder = false }
            request(app)
            main.post { completed(result) }
        }
    }

    private fun enqueue(context: Context, snapshot: SessionSnapshot, positionId: Int? = null) {
        val app = context.applicationContext as Application
        synchronized(lock) {
            if (selectingFolder) return // The folder-selection callback scans unfinished saves again.
            val id = snapshot.root.file.name
            val active = current
            if (active != null && active.snapshot.root.file.name == id && active.snapshot.signature == snapshot.signature &&
                control?.signal?.isCanceled != true) return
            pending[id] = Job(app, snapshot, positionId ?: pending[id]?.positionId)
            if (draining) return
            draining = true
            worker.execute(::drain)
        }
    }

    private fun drain() {
        while (true) {
            val job: Job
            val cancellation: CopyControl
            synchronized(lock) {
                // Finished positions take priority over older recovery work.
                val next = pending.entries.firstOrNull { it.value.positionId != null } ?: pending.entries.firstOrNull()
                if (selectingFolder || next == null) {
                    draining = false
                    current = null
                    control = null
                    return
                }
                job = next.value
                pending.remove(next.key)
                current = job
                cancellation = CopyControl(yieldToCapture = true)
                control = cancellation
            }
            var failure: Throwable? = null
            try {
                var lastUpdate = 0L
                PublicSessionStorage.syncSession(job.context, job.snapshot, control = cancellation) { done, total ->
                    val now = System.nanoTime()
                    if (done == total || now - lastUpdate > 1_500_000_000L) {
                        lastUpdate = now
                        val label = job.positionId?.let { "Saving position $it" } ?: "Finishing auto-save"
                        update(State(true, "$label · $done/$total files"))
                    }
                }.getOrThrow()
            } catch (error: Exception) {
                failure = error
            } finally {
                synchronized(lock) {
                    current = null
                    control = null
                    if (!selectingFolder && pending.isEmpty()) {
                        update(if (failure != null || PublicSessionStorage.error(job.context) != null) failedState()
                            else State(message = job.positionId?.let { "Position $it saved · Pictures/Q-VLP" }
                                ?: "Saved to Pictures/Q-VLP"))
                    }
                }
            }
        }
    }

    private fun failedState() = State(message = "Auto-save needs attention · Tap to retry", needsAttention = true)
}
