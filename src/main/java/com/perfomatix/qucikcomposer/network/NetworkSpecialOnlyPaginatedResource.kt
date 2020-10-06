package com.bigvand.surfboard.utils

import androidx.annotation.WorkerThread
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.paging.DataSource
import androidx.paging.LivePagedListBuilder
import androidx.paging.PageKeyedDataSource
import androidx.paging.PagedList
import com.bigvand.surfboard.models.ResponseErrorBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.IOException

abstract class NetworkSpecialOnlyPaginatedResource<ApiResponseType, PagingListType>(mSkipInt: Int) {

    private val mResult =
        MediatorLiveData<PagedResource<PagingListType, ApiResponseType>>()
    private var mStart = mSkipInt
    private var mEnd = mSkipInt
    private var mIsError = false


    private val mPageKeyedDataSource = object : PageKeyedDataSource<Int, PagingListType>() {
        override fun loadInitial(
            params: LoadInitialParams<Int>,
            callback: LoadInitialCallback<Int, PagingListType>
        ) {
            val limit = maxItemLimit() ?: params.requestedLoadSize
            createCall(mStart, limit).enqueue(
                object
                    : Callback<ApiResponseType> {
                    override fun onFailure(call: Call<ApiResponseType>, t: Throwable) {
                        mResult.postValue(PagedResource.error(AppException(t, null), 600))
                        mIsError = false
                    }

                    override fun onResponse(
                        call: Call<ApiResponseType>,
                        response: Response<ApiResponseType>
                    ) {
                        if (response.isSuccessful && response.code() in 200..299) {
                            response.body()?.let {
                                GlobalScope.launch(Dispatchers.Unconfined) {
                                    val resultList = convertResponse(it)
                                    GlobalScope.launch(Dispatchers.Main) {
                                        callback.onResult(
                                            resultList,
                                            mSkipInt,
                                            resultList?.size ?: 0
                                        )
                                        mResult.postValue(PagedResource.complete(it))
                                        if (resultList.isEmpty()) {
                                            if (mStart == mSkipInt) {
                                                mResult.postValue(PagedResource.empty())
                                            } else {
                                                mResult.postValue(PagedResource.end())
                                            }
                                        }
                                        mEnd += resultList.size
                                    }
                                }
                            } ?: let {
                                // empty
                                callback.onResult(emptyList(), mSkipInt, mSkipInt)
                                mResult.postValue(PagedResource.empty())
                            }
                        } else {
                            mIsError = false
                            val error = IOException(response.message())
                            val errorBody =
                                ResponseErrorBody(response.code().toString(), response.message())
                            mResult.postValue(
                                PagedResource.error(
                                    AppException(error, errorBody),
                                    response.code()
                                )
                            )
                        }
                    }

                }
            )
            //mSkipCount = params.requestedLoadSize
        }

        override fun loadAfter(
            params: LoadParams<Int>,
            callback: LoadCallback<Int, PagingListType>
        ) {
            val limit = maxItemLimit()?.let {
                it - mEnd
            } ?: params.requestedLoadSize
            val maxLimit = maxItemLimit()
            if (!mIsError && (maxLimit == null || maxLimit > mEnd)) {
                createCall(mEnd, limit).enqueue(
                    object : Callback<ApiResponseType> {
                        override fun onFailure(call: Call<ApiResponseType>, t: Throwable) {
                            mResult.postValue(PagedResource.error(AppException(t, null), 600))
                            mIsError = false
                        }

                        override fun onResponse(
                            call: Call<ApiResponseType>,
                            response: Response<ApiResponseType>
                        ) {
                            if (response.isSuccessful && response.code() in 200..299) {
                                response.body()?.let {
                                    GlobalScope.launch(Dispatchers.IO) {
                                        val resultList = convertResponse(it)
                                        GlobalScope.launch(Dispatchers.Main) {
                                            saveResultAndReInit(resultList)
                                            callback.onResult(resultList, params.key)
                                        }
                                    }
                                } ?: let {
                                    // empty
                                    callback.onResult(emptyList(), params.key)
                                    mResult.postValue(PagedResource.end())
                                }
                            } else {
                                mIsError = false
                                val error = IOException(response.message())
                                val errorBody =
                                    ResponseErrorBody(
                                        response.code().toString(),
                                        response.message()
                                    )
                                mResult.postValue(
                                    PagedResource.error(
                                        AppException(error, errorBody),
                                        response.code()
                                    )
                                )
                            }
                        }

                    }
                )
                mStart = mEnd
                mEnd += params.requestedLoadSize
            } else {
                callback.onResult(emptyList(), params.key)
            }
        }

        override fun loadBefore(
            params: LoadParams<Int>,
            callback: LoadCallback<Int, PagingListType>
        ) {
            val diff = if (mStart < params.requestedLoadSize) mStart else params.requestedLoadSize
            val backStart = mStart - diff
            if (!mIsError) {
                createCall(backStart, diff).enqueue(
                    object : Callback<ApiResponseType> {
                        override fun onFailure(call: Call<ApiResponseType>, t: Throwable) {
                            mResult.postValue(PagedResource.error(AppException(t, null), 600))
                            mIsError = false
                        }

                        override fun onResponse(
                            call: Call<ApiResponseType>,
                            response: Response<ApiResponseType>
                        ) {
                            if (response.isSuccessful && response.code() in 200..299) {
                                response.body()?.let {
                                    GlobalScope.launch(Dispatchers.IO) {
                                        val resultList = convertResponse(it)
                                        GlobalScope.launch(Dispatchers.Main) {
                                            saveResultAndReInit(resultList)
                                            callback.onResult(resultList, params.key)
                                        }
                                    }
                                    mEnd = mStart
                                    mStart = backStart
                                } ?: let {
                                    // empty
                                    callback.onResult(emptyList(), params.key)
                                    mResult.postValue(PagedResource.end())
                                }
                            } else {
                                mIsError = false
                                val error = IOException(response.message())
                                val errorBody =
                                    ResponseErrorBody(
                                        response.code().toString(),
                                        response.message()
                                    )
                                mResult.postValue(
                                    PagedResource.error(
                                        AppException(error, errorBody),
                                        response.code()
                                    )
                                )
                            }
                        }

                    }
                )
            } else {
                callback.onResult(emptyList(), params.key)
            }
        }

    }

    // returns a LiveData that represents the resource, implemented
    // in the base class.
    val asLiveData: LiveData<PagedResource<PagingListType, ApiResponseType>>
        get() = mResult

    @WorkerThread
    abstract fun createCall(skip: Int, limit: Int): Call<ApiResponseType>

    @WorkerThread
    abstract fun convertResponse(apiResponse: ApiResponseType): List<PagingListType>

    @WorkerThread
    abstract fun saveResultAndReInit(pageList: List<PagingListType>)

    abstract fun createPagedListConfig(): PagedList.Config

    protected open fun maxItemLimit(): Int? {
        return null
    }

    init {
        val pagedList = getProjectListingPage()
        mResult.postValue(PagedResource.loading())
        mResult.addSource(pagedList) {
            mResult.postValue(PagedResource.page(it))
        }
    }


    private fun getProjectListingPage(): LiveData<PagedList<PagingListType>> {
        val pagedListConfig = createPagedListConfig()
        val pagedList = LivePagedListBuilder<Int, PagingListType>(object :
            DataSource.Factory<Int, PagingListType>() {
            override fun create(): DataSource<Int, PagingListType> = mPageKeyedDataSource
        }, pagedListConfig).build()
        return pagedList
    }

    fun getSkipCount() = mStart
}