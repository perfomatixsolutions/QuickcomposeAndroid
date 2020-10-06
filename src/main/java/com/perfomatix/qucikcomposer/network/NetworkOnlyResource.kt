package com.bigvand.surfboard.utils

import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Custom class to implement Jetpack arch
 */
abstract class NetworkOnlyResource<ResultType> {

    private val result = MediatorLiveData<Resource<ResultType>>()

    // returns a LiveData that represents the resource, implemented
    // in the base class.
    val asLiveData: LiveData<Resource<ResultType>>
        get() = result

    // Called to create the API call.
    @MainThread
    protected abstract fun createCall(): LiveData<ApiResponse<ResultType>>

    // Called to save the result of the API response into the database
    @WorkerThread
    protected abstract fun saveCallResult(item: ResultType?)

    // Called when the fetch fails. The child class may want to reset components
    // like rate limiter.
    @MainThread
    private fun onFetchFailed() {
    }

    init {
        result.postValue(Resource.loading(null))
        fetchFromNetwork()
    }

    private fun fetchFromNetwork() {

        val apiResponse = createCall()

        result.addSource(apiResponse) { response ->
            result.removeSource(apiResponse)
            GlobalScope.launch(Dispatchers.IO) {
                if (response?.isSuccessful == true) {
                    saveResultAndReInit(response)
                } else {
                    onFetchFailed()
                    result.postValue(
                        Resource.error(
                            AppException(
                                response?.error,
                                response?.responseErrorBody
                            ), null, response.code
                        )
                    )
                }
            }

        }

    }

    @MainThread
    private fun saveResultAndReInit(response: ApiResponse<ResultType>) {
        GlobalScope.launch(Dispatchers.IO) {
            saveCallResult(response.body)
            result.postValue(Resource.success(response.body, response.code))
        }
    }
}