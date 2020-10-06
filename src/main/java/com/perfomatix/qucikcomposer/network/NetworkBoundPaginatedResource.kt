package com.bigvand.surfboard.utils

import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.paging.DataSource
import androidx.paging.LivePagedListBuilder
import androidx.paging.PagedList
import com.bigvand.surfboard.models.ResponseErrorBody
import kotlinx.coroutines.*
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import timber.log.Timber
import java.io.IOException

/**
 * A generic class that can provide a resource backed by both the sqlite database and the network.
 *
 *
 * You can read more about it in the [Architecture
 * Guide](https://developer.android.com/arch).
 * @param <ResultType>
 * @param <RequestType>
</RequestType></ResultType> */
abstract class NetworkBoundPaginatedResource<ApiResponseType, PagingListType>
@MainThread constructor() {
    companion object {
        private val mJob1 = Job()
        private val mJob2 = Job()
        private val mIoCoroutine = CoroutineScope(Dispatchers.IO + mJob1)
        private val mCpuCoroutine = CoroutineScope(Dispatchers.Default + mJob2)
    }

    private val result = MediatorLiveData<PagedResource<PagingListType, ApiResponseType>>()
    private var dbSource: LiveData<PagedList<PagingListType>>
    private var mSkipCount = 0
    private var mIsError = false
    private var mFistTime = false
    private var mApiInProgress = false


    init {
        mSkipCount = 0
        dbSource = getDataBaseListingPage()
        result.addSource(dbSource) { data ->
            setValue(PagedResource.page(data))
        }
        mIoCoroutine.launch {
            delay(100)
            val limit = maxItemLimit() ?: initItemLimit()
            fetchAPI(0, limit)
        }
    }

    @MainThread
    private fun setValue(newValue: PagedResource<PagingListType, ApiResponseType>) {
        if (result.value != newValue) {
            result.postValue(newValue)
        }
    }

    private fun fetchAPI(skip: Int, limit: Int) {
        if (!mApiInProgress && !mIsError) {
            mIsError = false
            mApiInProgress = true
            val apiCall = createCall(skip, limit)
            Timber.d("${apiCall.request().url().toString()} start, skip = $skip and limit = $limit")
            apiCall.enqueue(object : Callback<ApiResponseType> {
                override fun onFailure(call: Call<ApiResponseType>, t: Throwable) {
                    mApiInProgress = false
                    mIsError = true
                    setValue(PagedResource.complete(null))
                    val errorBody =
                        ResponseErrorBody("800", "Android Internal Error")
                    setValue(
                        PagedResource.error(
                            AppException(t, errorBody),
                            800
                        )
                    )
                    mIsError = true
                }

                override fun onResponse(
                    call: Call<ApiResponseType>,
                    response: Response<ApiResponseType>
                ) {
                    Timber.d("${call.request().url().toString()} onResponse")
                    mApiInProgress = false
                    if (response.isSuccessful && response.code() in 200..299) {
                        response.body()?.let { apiResponse ->
                            setValue(PagedResource.complete(apiResponse))
                            mCpuCoroutine.launch {
                                convertResponse(apiResponse)?.let { convertedResponse ->
                                    if (convertedResponse.isEmpty()) {
                                        if (mSkipCount == 0) {
                                            Timber.d("${call.request().url().toString()} empty")
                                            saveCall(convertedResponse)
                                            setValue(PagedResource.empty())
                                        } else {
                                            Timber.d("${call.request().url().toString()} end")
                                            setValue(PagedResource.end())
                                        }

                                    } else {
                                        maxItemLimit()?.let { max ->
                                            if (max < convertedResponse.size) {
                                                val item = convertedResponse.subList(0, max)
                                                saveCall(item)
                                            } else {
                                                saveCall(convertedResponse)
                                            }
                                        } ?: let {
                                            saveCall(convertedResponse)
                                        }
                                    }
                                    mFistTime = false
                                } ?: let {
                                    if (mSkipCount == 0) {
                                        Timber.d("${call.request().url().toString()} empty")
                                        setValue(PagedResource.empty())
                                        saveCall(emptyList())
                                    } else {
                                        Timber.d("${call.request().url().toString()} end")
                                        setValue(PagedResource.end())
                                    }
                                }

                            }
                        }
                    } else {
                        setValue(PagedResource.complete(null))
                        val error = IOException(response.message())
                        val errorBody =
                            ResponseErrorBody(response.code().toString(), response.message())
                        setValue(
                            PagedResource.error(
                                AppException(error, errorBody),
                                response.code()
                            )
                        )
                        mIsError = true
                    }
                }

            })
        }
    }

    protected open fun onFetchFailed() {}

    fun asLiveData() = result as LiveData<PagedResource<PagingListType, ApiResponseType>>

    @WorkerThread
    abstract fun convertResponse(apiResponse: ApiResponseType): List<PagingListType>?

    fun saveCall(item: List<PagingListType>?) {
        if (mSkipCount == 0) {
            clearInsert(item)
            /*if (item.isNullOrEmpty()) {
                result.value?.pageList?.dataSource?.invalidate()
            }*/
        } else {
            saveCallResult(item)
        }
        item?.let {
            mSkipCount += item.size
        }
    }

    @WorkerThread
    protected abstract fun saveCallResult(item: List<PagingListType>?)

    @MainThread
    protected abstract fun loadFromDb(): DataSource.Factory<Int, PagingListType>

    @MainThread
    protected abstract fun createCall(skip: Int, limit: Int): Call<ApiResponseType>

    private fun getDataBaseListingPage(): LiveData<PagedList<PagingListType>> {
        val pagedListConfig = createPagedListConfig()
        val pagedList =
            LivePagedListBuilder(loadFromDb(), pagedListConfig).setBoundaryCallback(object :
                PagedList.BoundaryCallback<PagingListType>() {
                override fun onZeroItemsLoaded() {
                    mFistTime = true
                    setValue(PagedResource.loading())
                    super.onZeroItemsLoaded()
                }

                override fun onItemAtEndLoaded(itemAtEnd: PagingListType) {
                    try {
                        maxItemLimit()?.let {
                            if (mSkipCount < it)
                                fetchAPI(mSkipCount, it)
                        } ?: let {
                            mIoCoroutine.launch {
                                withContext(mIoCoroutine.coroutineContext) {
                                    fetchAPI(mSkipCount, initItemLimit())
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Timber.w(e)
                    }
                    super.onItemAtEndLoaded(itemAtEnd)
                }

                override fun onItemAtFrontLoaded(itemAtFront: PagingListType) {
                    mFistTime = true
                    super.onItemAtFrontLoaded(itemAtFront)
                }
            }).build()
        return pagedList
    }

    abstract fun createPagedListConfig(): PagedList.Config

    abstract fun clearInsert(item: List<PagingListType>?)

    protected open fun maxItemLimit(): Int? = null

    protected open fun initItemLimit(): Int = 50
}