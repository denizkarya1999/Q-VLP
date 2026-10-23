package com.developer27.qvlp.capture

import android.content.Context
import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.io.File
import java.util.concurrent.Executors

/** One process-wide publication queue. Activity recreation cannot duplicate or strand its work. */
internal object SessionPublisher {
    data class State(val running: Boolean = false, val message: String = "Sessions save to Pictures/Q-VLP · Tap for options")
    private data class Job(val context: Application, val snapshot: SessionSnapshot, val verify: Boolean)
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
    private var paused = false
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

    private fun preferences(context: Context) = context.getSharedPreferences("public_session_storage", Context.MODE_PRIVATE)
    fun isPaused(context: Context) = preferences(context).getBoolean("paused", false)

    fun pause(context: Context) {
        preferences(context).edit().putBoolean("paused", true).apply()
        synchronized(lock) {
            paused = true
            pending.clear()
            control?.cancel()
        }
        update(State(message = "Folder saving paused · Tap to resume. Captures remain safe in the app."))
    }

    /** Called on the capture I/O queue only after the attempt is closed. Never blocks that queue on SAF. */
    fun captureReady(context: Context, session: File) {
        runCatching { enqueue(context, PublicSessionStorage.snapshot(session), false) }.onFailure {
            update(State(message = "Folder save needs attention · Open Sessions → Storage to retry."))
        }
    }

    /** Local receipts identify unfinished sessions. Completed public folders are not recreated on resume. */
    fun request(context: Context, verify: Boolean = false, manual: Boolean = false) {
        val app = context.applicationContext
        if (!PublicSessionStorage.configured(app)) {
            update(State(message = "Choose Pictures/Q-VLP · Tap to set up session storage"))
            return
        }
        synchronized(lock) {
            if (manual) {
                preferences(app).edit().putBoolean("paused", false).apply()
                paused = false
            } else paused = isPaused(app)
            if (paused) {
                update(State(message = "Folder saving paused · Tap to resume. Captures remain safe in the app."))
                return
            }
            if (scanQueued && !manual) return
            scanQueued = true
        }
        SessionFiles.io.execute {
            try {
                SessionFiles.directories(app).forEach { session ->
                    val snapshot = PublicSessionStorage.snapshot(session)
                    if (verify || PublicSessionStorage.needsSave(app, snapshot)) enqueue(app, snapshot, verify)
                }
                synchronized(lock) {
                    if (!draining && !paused) update(State(message = idleMessage(app)))
                }
            } catch (error: Exception) {
                update(State(message = "Cannot prepare folder saves: ${error.message}. Tap to retry."))
            } finally {
                synchronized(lock) { scanQueued = false }
            }
        }
    }

    fun selectFolder(context: Context, uri: Uri, completed: (Result<Unit>) -> Unit) {
        val app = context.applicationContext
        pause(app)
        worker.execute {
            val result = runCatching { PublicSessionStorage.select(app, uri) }
            if (result.isSuccess) request(app, manual = true)
            main.post { completed(result) }
        }
    }

    private fun enqueue(context: Context, snapshot: SessionSnapshot, verify: Boolean) {
        val app = context.applicationContext
        synchronized(lock) {
            paused = isPaused(app)
            if (paused) return
            val id = snapshot.root.file.name
            val active = current
            if (active != null && active.snapshot.root.file.name == id && active.snapshot.signature == snapshot.signature &&
                (!verify || active.verify) && control?.signal?.isCanceled != true) return
            pending[id] = Job(app as Application, snapshot, verify || pending[id]?.verify == true)
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
                val next = pending.entries.firstOrNull()
                if (paused || next == null) {
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
            try {
                var lastUpdate = 0L
                PublicSessionStorage.syncSession(job.context, job.snapshot, job.verify, cancellation) { done, total ->
                    val now = System.nanoTime()
                    if (done == total || now - lastUpdate > 1_500_000_000L) {
                        lastUpdate = now
                        synchronized(lock) {
                            if (!paused) update(State(true,
                                "${if (job.verify) "Verifying" else "Saving to"} Pictures/Q-VLP · $done/$total files · Tap for options"))
                        }
                    }
                }
            } catch (error: Exception) {
                update(State(message = "Folder save needs attention: ${error.message}. Tap to retry."))
            } finally {
                synchronized(lock) {
                    current = null
                    control = null
                    if (!paused && pending.isEmpty()) update(State(message = idleMessage(job.context)))
                }
            }
        }
    }

    private fun idleMessage(context: Context): String = if (PublicSessionStorage.error(context) == null)
        "Saved to Pictures/Q-VLP · Tap for options"
        else "Some folder saves need attention · Tap to retry. Original files are safe."
}
