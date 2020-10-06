package com.bigvand.surfboard.utils

import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * Custom class for get Non-Json Response from API
 */
abstract class ScalarsNetworkOnlyResource<ResultType> {
    private val result = MediatorLiveData<Resource<ResultType>>()

    val asLiveData: LiveData<Resource<ResultType>>
        get() = result

    // Called to create the API call.
    @MainThread
    protected abstract fun createCall(): Call<ResultType>

    // Called to save the result of the API response into the database
    @WorkerThread
    protected abstract fun saveCallResult(item: ResultType?)

    @MainThread
    private fun onFetchFailed() {
    }

    init {
        result.postValue(Resource.loading(null))
        fetchFromNetwork()
    }

    private fun fetchFromNetwork() {
        val call = createCall()
        call.enqueue(object : Callback<ResultType> {
            override fun onResponse(call: Call<ResultType>, response: Response<ResultType>) {
                if (response.isSuccessful) {
                    saveResultAndReInit(ApiResponse(response))
                } else {
                    result.postValue(Resource.error(AppException(Exception(response.message()),null), null, response.code()))
                }
            }

            override fun onFailure(call: Call<ResultType>, t: Throwable) {
                onFetchFailed()
                result.postValue(Resource.error(AppException(t,null), null, 0))
            }

        })
    }

    @MainThread
    private fun saveResultAndReInit(response: ApiResponse<ResultType>) {
        GlobalScope.launch(Dispatchers.IO) {
            saveCallResult(response.body)
            result.postValue(Resource.success(response.body, response.code))
        }
    }

}