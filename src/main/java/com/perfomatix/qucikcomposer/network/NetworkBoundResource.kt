package com.bigvand.surfboard.utils

import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import com.bigvand.surfboard.models.ResponseErrorBody
import kotlinx.coroutines.*
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
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
@Suppress("LeakingThis")
abstract class NetworkBoundResource<ApiResponseType, DataBaseModelType>
@MainThread constructor(/*private val appExecutors: AppExecutors*/) {
    companion object {
        private val mJob1 = Job()
        private val mJob2 = Job()
        private val mIoCoroutine = CoroutineScope(Dispatchers.IO + mJob1)
        private val mCpuCoroutine = CoroutineScope(Dispatchers.Default + mJob2)
    }

    private var mApiInProgress = false
    private val result = MediatorLiveData<Resource<DataBaseModelType>>()

    init {
        val dbSource = loadFromDb()
        setValue(Resource.loading(null))
        result.addSource(dbSource) { data ->
            setValue(Resource.success(data, 200))
        }
        mIoCoroutine.launch {
            fetchFromNetwork()
        }
    }

    @MainThread
    private fun setValue(newValue: Resource<DataBaseModelType>) {
        if (result.value != newValue) {
            result.postValue(newValue)
        }
    }

    private fun fetchFromNetwork() {
        val call = createCall()
        call.enqueue(object : Callback<ApiResponseType> {
            override fun onFailure(call: Call<ApiResponseType>, t: Throwable) {
                setValue(
                    Resource.error(
                        AppException(
                            t,
                            ResponseErrorBody("800", "Android model error")
                        ), null, 800
                    )
                )
            }

            override fun onResponse(
                call: Call<ApiResponseType>,
                response: Response<ApiResponseType>
            ) {
                if (response.isSuccessful && response.code() in 200..299) {
                    response.body()?.let { apiResponse ->
                        mIoCoroutine.launch {
                            withContext(mCpuCoroutine.coroutineContext) {
                                convertResponse(apiResponse)?.let { newResponse ->
                                    saveCallResult(newResponse)
                                } ?: let {
                                    saveCallResult(null)
                                }
                            }
                        }
                    } ?: let {
                        saveCallResult(null)
                    }

                } else {
                    val error = IOException(response.message())
                    val errorBody =
                        ResponseErrorBody(response.code().toString(), response.message())
                    setValue(
                        Resource.error(
                            AppException(error, errorBody),
                            null,
                            response.code()
                        )
                    )
                }
            }

        })
    }

    protected open fun onFetchFailed() {}

    public val asLiveData: LiveData<Resource<DataBaseModelType>> = result

    @WorkerThread
    protected abstract fun saveCallResult(item: DataBaseModelType?)

    @MainThread
    protected abstract fun loadFromDb(): LiveData<DataBaseModelType>

    @MainThread
    protected abstract fun createCall(): Call<ApiResponseType>

    @WorkerThread
    protected abstract fun convertResponse(apiResponse: ApiResponseType): DataBaseModelType?
}