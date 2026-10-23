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
    private sealed interface Work
    private data class Job(val context: Application, val snapshot: SessionSnapshot, val positionId: Int?) : Work
    private data class Deletion(val context: Application, val id: String,
        val callbacks: MutableList<(Result<Unit>) -> Unit> = mutableListOf()) : Work
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { action -> Thread({
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        action.run()
    }, "qvlp-public-saves") }
    private val pending = linkedMapOf<String, Job>()
    private val deletions = linkedMapOf<String, Deletion>()
    private var deleting: Deletion? = null
    private val observers = mutableSetOf<(State) -> Unit>()
    private var current: Job? = null
    private var control: CopyControl? = null
    private var draining = false
    private var selectingFolder = false
    private var scanQueued = false
    private var snapshotsPrepared = false
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

    /** Reset old confirmations without cancelling saves or hiding storage problems. */
    fun sessionChanged() {
        synchronized(lock) {
            if (!state.needsAttention) update(State(running = current != null || pending.isNotEmpty()))
        }
    }

    /** Check identity and publish under the same lock as reset, including delayed worker callbacks. */
    private fun updateForSession(job: Job, value: State) {
        synchronized(lock) {
            update(if (SessionFiles.activeSessionId(job.context) == job.snapshot.root.file.name) value
                else State(running = value.running))
        }
    }

    private fun enableAutoSave(context: Context) {
        // Upgrade earlier versions that allowed a persistent pause. Every position now auto-saves.
        val prefs = context.getSharedPreferences("public_session_storage", Context.MODE_PRIVATE)
        if (prefs.contains("paused")) prefs.edit().remove("paused").apply()
    }

    /** Called on the capture I/O queue only after the attempt and metadata are closed. */
    fun captureReady(context: Context, session: File, positionId: Int) {
        enableAutoSave(context)
        if (SessionFiles.isDeleting(context, session.name)) return
        runCatching {
            prepareSnapshots(context)
            SessionCsv.update(session)
            enqueue(context, PublicSessionStorage.snapshot(session), positionId)
        }.onFailure {
            PublicSessionStorage.recordError(context, session.name, it)
            update(State(message = "Could not prepare auto-save · Tap to retry", needsAttention = true))
        }
    }

    /** Recover unfinished saves on reopening. Already saved sessions are left alone. */
    fun request(context: Context) {
        val app = context.applicationContext
        enableAutoSave(app)
        synchronized(lock) {
            if (scanQueued || selectingFolder) return
            scanQueued = true
        }
        SessionFiles.io.execute {
            try {
                SessionFiles.pendingDeletions(app).forEach { id ->
                    runCatching {
                        SessionFiles.beginDeletion(app, id)
                        enqueueDeletion(app, id)
                    }.onFailure { PublicSessionStorage.recordError(app, id, it) }
                }
                if (!PublicSessionStorage.configured(app)) {
                    update(State(message = "Set up auto-save · Choose Pictures/Q-VLP", needsAttention = true))
                    return@execute
                }
                prepareSnapshots(app)
                SessionFiles.directories(app).filterNot { SessionFiles.isDeleting(app, it.name) }.forEach { session ->
                    runCatching {
                        SessionCsv.update(session)
                        val snapshot = PublicSessionStorage.snapshot(session)
                        if (PublicSessionStorage.needsSave(app, snapshot)) enqueue(app, snapshot)
                    }.onFailure { PublicSessionStorage.recordError(app, session.name, it) }
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

    fun delete(context: Context, id: String, prepared: (Boolean) -> Unit, completed: (Result<Unit>) -> Unit) {
        val app = context.applicationContext
        SessionFiles.io.execute {
            runCatching { SessionFiles.beginDeletion(app, id) }.fold(onSuccess = { active ->
                main.post { prepared(active) }
                enqueueDeletion(app, id, completed)
            }, onFailure = { error -> main.post { completed(Result.failure(error)) } })
        }
    }

    private fun enqueueDeletion(context: Context, id: String, completed: ((Result<Unit>) -> Unit)? = null) {
        synchronized(lock) {
            pending.remove(id)?.snapshot?.discard()
            if (current?.snapshot?.root?.file?.name == id) control?.cancel()
            val job = deleting?.takeIf { it.id == id } ?: deletions.getOrPut(id) {
                Deletion(context.applicationContext as Application, id)
            }
            completed?.let { job.callbacks.add(it) }
            startWorker()
        }
    }

    /** Called with lock held. Deletions and publications share one worker, preventing resurrection. */
    private fun startWorker() {
        if (draining || selectingFolder) return
        draining = true
        worker.execute(::drain)
    }

    private fun removeSession(job: Deletion) {
        update(State(true, "Deleting session…"))
        val result = runCatching<Unit> {
            PublicSessionStorage.deleteSession(job.context, job.id)
            SessionFiles.deletePrivateFiles(job.context, job.id)
            // Finalize on io so a recovery scan cannot observe a marker that disappears mid-scan.
            SessionFiles.io.submit {
                PublicSessionStorage.forgetSession(job.context, job.id)
                SessionFiles.finishDeletion(job.context, job.id)
            }.get()
        }
        result.exceptionOrNull()?.let { PublicSessionStorage.recordError(job.context, job.id, it) }
        val callbacks = synchronized(lock) {
            deleting = null
            job.callbacks.toList()
        }
        update(if (result.isFailure) State(message = "Deletion needs attention · Tap to retry", needsAttention = true)
            else if (PublicSessionStorage.error(job.context) != null) failedState()
            else State(message = "Session deleted · Pictures/Q-VLP"))
        main.post { callbacks.forEach { it(result) } }
    }

    fun selectFolder(context: Context, uri: Uri, completed: (Result<Unit>) -> Unit) {
        val app = context.applicationContext
        synchronized(lock) {
            selectingFolder = true
            pending.values.forEach { it.snapshot.discard() }
            pending.clear()
            control?.cancel()
        }
        worker.execute {
            val result = runCatching { PublicSessionStorage.select(app, uri) }
            synchronized(lock) { selectingFolder = false }
            SessionFiles.io.execute { request(app) }
            main.post { completed(result) }
        }
    }

    /** Only accessed on the capture I/O queue, before its first snapshot is enqueued. */
    private fun prepareSnapshots(context: Context) {
        if (!snapshotsPrepared) {
            PublicSessionStorage.clearAbandonedSnapshots(context)
            snapshotsPrepared = true
        }
    }

    private fun enqueue(context: Context, snapshot: SessionSnapshot, positionId: Int? = null) {
        val app = context.applicationContext as Application
        if (SessionFiles.isDeleting(app, snapshot.root.file.name)) return
        val frozen = PublicSessionStorage.freeze(app, snapshot)
        synchronized(lock) {
            val id = snapshot.root.file.name
            if (selectingFolder || SessionFiles.isDeleting(app, id)) { frozen.discard(); return }
            val active = current
            if (active != null && active.snapshot.root.file.name == id && active.snapshot.signature == snapshot.signature &&
                control?.signal?.isCanceled != true) { frozen.discard(); return }
            val previous = pending[id]
            pending[id] = Job(app, frozen, positionId ?: previous?.positionId)
            previous?.snapshot?.discard()
            startWorker()
        }
    }

    private fun drain() {
        while (true) {
            val work: Work = synchronized(lock) {
                val deletion = deletions.entries.firstOrNull()
                val next = pending.entries.firstOrNull { it.value.positionId != null } ?: pending.entries.firstOrNull()
                if (selectingFolder || (deletion == null && next == null)) {
                    draining = false
                    current = null
                    control = null
                    return
                }
                if (deletion != null) {
                    deletions.remove(deletion.key)
                    deleting = deletion.value
                    deletion.value
                } else {
                    val job = requireNotNull(next).value
                    pending.remove(next.key)
                    current = job
                    control = CopyControl(yieldToCapture = true)
                    job
                }
            }
            if (work is Deletion) {
                removeSession(work)
                continue
            }
            val job = work as Job
            val cancellation = requireNotNull(control)
            var failure: Throwable? = null
            try {
                var lastUpdate = 0L
                PublicSessionStorage.syncSession(job.context, job.snapshot, control = cancellation) { done, total ->
                    val now = System.nanoTime()
                    if (done == total || now - lastUpdate > 1_500_000_000L) {
                        lastUpdate = now
                        val label = job.positionId?.let { "Saving position $it" } ?: "Finishing auto-save"
                        updateForSession(job, State(true, "$label · $done/$total files"))
                    }
                }.getOrThrow()
            } catch (error: Exception) {
                failure = error
            } finally {
                job.snapshot.discard()
                synchronized(lock) {
                    current = null
                    control = null
                    if (!selectingFolder && pending.isEmpty() && deletions.isEmpty()) {
                        if (failure != null || PublicSessionStorage.error(job.context) != null) update(failedState())
                        else updateForSession(job, State(message = job.positionId?.let { "Position $it saved · Pictures/Q-VLP" }
                            ?: "Saved to Pictures/Q-VLP"))
                    }
                }
            }
        }
    }

    private fun failedState() = State(message = "Auto-save needs attention · Tap to retry", needsAttention = true)
}
