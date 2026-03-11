package com.bmdu.d_shieldchild.APi

import com.bmdu.d_shieldchild.APi.Models.ActivateDeviceResponse
import com.bmdu.d_shieldchild.APi.Models.DeviceStatusRequest
import com.bmdu.d_shieldchild.APi.Models.DeviceStatusResponse
import com.bmdu.d_shieldchild.APi.Models.RetailerDetailsResponse
import com.bmdu.d_shieldchild.APi.Models.UpdateSimRequest
import com.bmdu.d_shieldchild.APi.Models.UpdateSimResponse
import com.bmdu.d_shieldchild.APi.Models.registerRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface ApiService {
    @POST("device/activate")
    suspend fun registerDevice(
        @Body request: registerRequest
    ): Response<ActivateDeviceResponse>


    @POST("device/status")
    suspend fun getDeviceStatus(
        @Body request: DeviceStatusRequest
    ): Response<DeviceStatusResponse>


    @POST("device/sim-change")
    suspend fun UpdateSimStatus(
        @Body request: UpdateSimRequest
    ): Response<UpdateSimResponse>


    @GET("api/retailer-details")
    suspend fun getRetailerDetails(
        @Query("imei") imei: String
    ): RetailerDetailsResponse

//    @POST("device/fcm-token")
//    suspend fun sendFcm(
//        @Body request: FcmTokenRequest
//    ): Response<FcmTokenResponse>
}