package com.example.bus.tdx

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

interface TdxApi {
    @FormUrlEncoded
    @POST("auth/realms/TDXConnect/protocol/openid-connect/token")
    suspend fun getToken(
        @Field("grant_type") grantType: String,
        @Field("client_id") clientId: String,
        @Field("client_secret") clientSecret: String
    ): Response<TdxToken>

    // --- Static Data APIs ---

    @GET("api/basic/v2/Bus/Route/City/{City}")
    suspend fun getBusRoutes(
        @Header("Authorization") authorization: String,
        @Path("City") city: String,
        @Query("\$filter") filter: String,
        @Query("\$top") top: Int,
        @Query("\$format") format: String
    ): Response<List<BusRoute>>

    @GET("api/basic/v2/Bus/Stop/City/{City}")
    suspend fun getBusStops(
        @Header("Authorization") authorization: String,
        @Path("City") city: String,
        @Query("\$spatialFilter") spatialFilter: String?,
        @Query("\$top") top: Int,
        @Query("\$format") format: String
    ): Response<List<BusStop>>

    @GET("api/basic/v2/Bus/StopOfRoute/City/{City}")
    suspend fun getStopOfRoute(
        @Header("Authorization") authorization: String,
        @Path("City") city: String,
        @Query("\$filter") filter: String,
        @Query("\$format") format: String
    ): Response<List<StopOfRoute>>
    
    @GET("api/basic/v2/Bus/Shape/City/{City}")
    suspend fun getBusShape(
        @Header("Authorization") authorization: String,
        @Path("City") city: String,
        @Query("\$filter") filter: String,
        @Query("\$format") format: String
    ): Response<List<BusShape>>

    // --- Real-time Data APIs ---

    @GET("api/basic/v2/Bus/RealTimeByFrequency/City/{City}")
    suspend fun getRealTimeBusPositions(
        @Header("Authorization") authorization: String,
        @Path("City") city: String,
        @Query("\$filter") filter: String,
        @Query("\$format") format: String
    ): Response<List<BusRealTimePosition>>

    @GET("api/basic/v2/Bus/EstimatedTimeOfArrival/City/{City}")
    suspend fun getEstimatedTimeOfArrivalForRoute(
        @Header("Authorization") authorization: String,
        @Path("City") city: String,
        @Query("\$filter") filter: String,
        @Query("\$format") format: String
    ): Response<List<BusArrival>>

    @GET("api/basic/v2/Bus/EstimatedTimeOfArrival/City/{City}/{RouteName}")
    suspend fun getBusEstimatedTimeOfArrival(
        @Header("Authorization") authorization: String,
        @Path("City") city: String,
        @Path("RouteName") routeName: String,
        @Query("\$filter") filter: String?,
        @Query("\$format") format: String
    ): Response<List<StationBusEstimateTime>>

    // --- New/Modified for Raw Response Logging --- 

    @GET("api/basic/v2/Bus/Stop/Route/City/{City}")
    suspend fun getRoutesPassingStop(
        @Header("Authorization") authorization: String,
        @Path("City") city: String,
        @Query("\$filter") filter: String,
        @Query("\$top") top: Int,
        @Query("\$format") format: String
    ): Response<ResponseBody>

    @GET("api/basic/v2/Bus/EstimatedTimeOfArrival/City/{City}")
    suspend fun getEstimatedArrivalsForRoutesAtStop(
        @Header("Authorization") authorization: String,
        @Path("City") city: String,
        @Query("\$filter") filter: String,
        @Query("\$top") top: Int,
        @Query("\$format") format: String
    ): Response<ResponseBody>

    // --- (NearbyStopsScreen 需求) ---

    @GET("api/basic/v2/Bus/Station/City/{City}")
    suspend fun getStationsInCity(
        @Header("Authorization") authorization: String,
        @Path("City") city: String,
        @Query("\$top") top: Int,
        @Query("\$format") format: String
    ): Response<List<BusStation>>

    @GET("api/advanced/v2/Bus/Station/NearBy")
    suspend fun getNearbyStations(
        @Header("Authorization") authorization: String,
        @Query("\$spatialFilter") spatialFilter: String,
        @Query("\$top") top: Int,
        @Query("\$format") format: String
    ): Response<List<BusStation>>

    @GET("api/advanced/v2/Bus/EstimatedTimeOfArrival/City/{City}/PassThrough/Station/{StationID}")
    suspend fun getEstimatedArrivalsForStation(
        @Header("Authorization") authorization: String,
        @Path("City") city: String,
        @Path("StationID") stationID: String,
        @Query("\$top") top: Int,
        @Query("\$format") format: String
    ): Response<List<StationBusEstimateTime>>

    /**
     * 取得指定縣市、站牌的所有路線資料
     * Ref: https://tdx.transportdata.tw/api-service/swagger/basic/2cc9b888-a592-496f-99de-9ab887333504#/Bus/BusApi_Route_PassThrough_2182
     */
    @GET("api/advanced/v2/Bus/Route/City/{City}/PassThrough/Station/{StationID}")
    suspend fun getRoutesPassingThroughStation(
        @Header("Authorization") authorization: String,
        @Path("City") city: String,
        @Path("StationID") stationId: String,
        @Query("\$format") format: String = "JSON",
        @Query("\$filter") filter: String? = null, // 可選的過濾條件
        @Query("\$top") top: Int? = null,      // 可選的筆數限制
        @Query("\$skip") skip: String? = null    // 可選的跳過筆數
    ): Response<List<BusRoute>> // 假設回傳的是 BusRoute 列表
}
