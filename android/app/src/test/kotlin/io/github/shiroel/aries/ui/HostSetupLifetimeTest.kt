package io.github.shiroel.aries.ui

import io.github.shiroel.aries.core.AriesCoreInterface
import io.github.shiroel.aries.core.Host
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.HostPairingDetails
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class HostSetupLifetimeTest {
    private class PausedKeyWork : CoroutineDispatcher() {
        private val pending = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.addLast(block) }
        fun runPending() { while (pending.isNotEmpty()) pending.removeFirst().run() }
    }

    // Regression: leaving the scanner after the core saves a Host must not skip the model refresh.
    @Test fun leavingScannerDuringPairingStillRefreshesSavedHosts() = runTest {
        val reply = CompletableDeferred<Unit>()
        val fake = FakeCore().apply {
            ensureKey()
            pairingDetails = HostPairingDetails("desk.example", 22u, "me", "SHA256:pin", 9999999999u)
        }
        val core = object : AriesCoreInterface by fake {
            override suspend fun pairHostQr(name: String, qr: String): Host {
                val saved = fake.pairHostQr(name, qr)
                reply.await()
                return saved
            }
        }
        val model = HostsModel(core, backgroundScope)
        val scanner = launch { model.pairHostQr("Desk", "temporary credential") }
        runCurrent()
        scanner.cancel()
        reply.complete(Unit)
        runCurrent()
        assertEquals(listOf("Desk"), model.hosts.map { it.name })
    }
    // Regression: an edit committed before navigation must update the Host list after its delayed reply.
    @Test fun leavingEditorDuringSaveStillRefreshesEndpoint() = runTest {
        val reply = CompletableDeferred<Unit>()
        val fake = FakeCore()
        val host = fake.addHost("Desk", Endpoint.Ssh("old.example", 22u, "me"))
        val core = object : AriesCoreInterface by fake {
            override suspend fun editHost(hostId: String, name: String, endpoint: Endpoint): Host {
                val saved = fake.editHost(hostId, name, endpoint)
                reply.await()
                return saved
            }
        }
        val model = HostsModel(core, backgroundScope)
        val editor = launch { model.saveHost(host, "Desk", "new.example", 2222, "me") }
        runCurrent()
        editor.cancel()
        reply.complete(Unit)
        runCurrent()
        assertEquals(Endpoint.Ssh("new.example", 2222u, "me"), model.hosts.single().endpoint)
    }

    // Regression: closing the import UI while key work is dispatched must not cancel a submitted import.
    @Test fun leavingImporterWhileKeyWorkIsPendingStillRefreshesKeys() = runTest {
        val keyWork = PausedKeyWork()
        val model = HostsModel(FakeCore(), backgroundScope, keyWork = keyWork)
        val importer = launch { model.importKey("", "private test key", "") }
        runCurrent()
        importer.cancel()
        keyWork.runPending()
        runCurrent()
        assertEquals("SHA256:fake-imported", model.key?.fingerprint)
    }

    // Regression: key replacement must refresh the retained model even when its confirmation UI leaves.
    @Test fun leavingRecoveryWhileKeyWorkIsPendingStillRefreshesReplacement() = runTest {
        val fake = FakeCore().apply { ensureKey() }
        val keyWork = PausedKeyWork()
        val model = HostsModel(fake, backgroundScope, keyWork = keyWork)
        val recovery = launch { model.recoverKey("default") }
        runCurrent()
        recovery.cancel()
        keyWork.runPending()
        runCurrent()
        assertEquals("SHA256:recovered", model.key?.fingerprint)
    }

}
