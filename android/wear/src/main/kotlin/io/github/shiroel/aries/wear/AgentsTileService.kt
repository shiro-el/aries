package io.github.shiroel.aries.wear

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AgentsTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        CallbackToFutureAdapter.getFuture { result ->
            val job = scope.launch {
                val store = WatchStateStore(this@AgentsTileService)
                store.hydrate(this@AgentsTileService)
                result.set(WatchTile.render(this@AgentsTileService, store.summary(), System.currentTimeMillis()))
            }
            result.addCancellationListener({ job.cancel() }, mainExecutor)
            "Aries Tile"
        }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        CallbackToFutureAdapter.getFuture { result ->
            result.set(ResourceBuilders.Resources.Builder().setVersion("1").build())
            "Aries Tile resources"
        }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
