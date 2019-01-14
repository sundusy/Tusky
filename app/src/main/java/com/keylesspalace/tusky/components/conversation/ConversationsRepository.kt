package com.keylesspalace.tusky.components.conversation

import androidx.annotation.MainThread
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Transformations
import androidx.paging.Config
import androidx.paging.toLiveData
import com.keylesspalace.tusky.db.AppDatabase
import com.keylesspalace.tusky.entity.Conversation
import com.keylesspalace.tusky.network.MastodonApi
import com.keylesspalace.tusky.util.Listing
import com.keylesspalace.tusky.util.NetworkState
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationsRepository @Inject constructor(val mastodonApi: MastodonApi, val db: AppDatabase) {

    private val ioExecutor = Executors.newSingleThreadExecutor()

    companion object {
        private const val DEFAULT_PAGE_SIZE = 20
    }

    @MainThread
    private fun refresh(accountId: Long): LiveData<NetworkState> {
        val networkState = MutableLiveData<NetworkState>()
        networkState.value = NetworkState.LOADING

        mastodonApi.getConversations(null, DEFAULT_PAGE_SIZE).enqueue(
                object : Callback<List<Conversation>> {
                    override fun onFailure(call: Call<List<Conversation>>, t: Throwable) {
                        // retrofit calls this on main thread so safe to call set value
                        networkState.value = NetworkState.error(t.message)
                    }

                    override fun onResponse(call: Call<List<Conversation>>, response: Response<List<Conversation>>) {
                        ioExecutor.execute {
                            db.runInTransaction {
                                db.conversationDao().conversationsForAccount(accountId)
                                insertResultIntoDb(accountId, response.body())
                            }
                            // since we are in bg thread now, post the result.
                            networkState.postValue(NetworkState.LOADED)
                        }
                    }
                }
        )
        return networkState
    }


    @MainThread
    fun conversations(accountId: Long, pageSize: Int): Listing<ConversationEntity> {
        // create a boundary callback which will observe when the user reaches to the edges of
        // the list and update the database with extra data.
        val boundaryCallback = ConversationsBoundaryCallback(
                accountId = accountId,
                mastodonApi = mastodonApi,
                handleResponse = this::insertResultIntoDb,
                ioExecutor = ioExecutor,
                networkPageSize = DEFAULT_PAGE_SIZE)
        // we are using a mutable live data to trigger refresh requests which eventually calls
        // refresh method and gets a new live data. Each refresh request by the user becomes a newly
        // dispatched data in refreshTrigger
        val refreshTrigger = MutableLiveData<Unit>()
        val refreshState = Transformations.switchMap(refreshTrigger) {
            refresh(accountId)
        }

        // We use toLiveData Kotlin extension function here, you could also use LivePagedListBuilder
        val livePagedList =  db.conversationDao().conversationsForAccount(0).toLiveData(
                config = Config(pageSize = pageSize, prefetchDistance = pageSize / 2),
                boundaryCallback = boundaryCallback)

        return Listing(
                pagedList = livePagedList,
                networkState = boundaryCallback.networkState,
                retry = {
                    boundaryCallback.helper.retryAllFailed()
                },
                refresh = {
                    refreshTrigger.value = null
                },
                refreshState = refreshState
        )
    }

    private fun insertResultIntoDb(accountId: Long, result: List<Conversation>?) {
        result?.let { conversations ->
                db.conversationDao().insert(conversations.map { it.mapToEntity(accountId) })
        }
    }
}