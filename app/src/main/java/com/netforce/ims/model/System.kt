package com.netforce.ims.model

data class SystemInfo(
    val appVersionName: String = "",
    val androidVersion: String = "",
    val deviceModel: String = "",
    val systemVersion: String = "",
    val securityPatchVersion: String = "",
)