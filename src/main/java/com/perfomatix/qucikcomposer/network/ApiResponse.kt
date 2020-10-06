package com.bigvand.surfboard.utils

import com.google.gson.Gson
import com.perfomatix.qucikcomposer.network.ResponseError
import com.perfomatix.qucikcomposer.network.ResponseErrorBody

import retrofit2.Response
import java.io.IOException

/**
 * Base Api response model
 */
class ApiResponse<T> {

    val code: Int
    val body: T?
    var responseErrorBody: ResponseErrorBody? = null
    val error: Throwable?

    val isSuccessful: Boolean
        get() = code in 200..299


    constructor(error: Throwable) {
        code = 500
        body = null
        this.error = error
    }

    constructor(response: retrofit2.Response<T>) {
        code = response.code()
        if (response.isSuccessful) {
            body = response.body()
            error = null
        } else {
            var message: String? = null
            if (response.errorBody() != null) {
                try {
                    message = response.errorBody()!!.string()
                } catch (ignored: IOException) {
                }
            }
            if (message == null || message.trim { it <= ' ' }.isEmpty()) {
                message = response.message()
            }
            error = IOException(message)
            responseErrorBody = Gson().fromJson(message, ResponseError::class.java).error
            body = null
        }
    }
}
