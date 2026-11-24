package com.guardstar.app.data.model

import kotlinx.serialization.Serializable

/** 关系摘要，登录响应与关系列表共用。 */
@Serializable
data class RelationSummary(
    val relationId: String,
    val peerNickname: String = "",
    val role: String = "ward",
    val status: String = "active",
    val createdAt: String = "",
    /** 被守护人最近注册的设备 ID，用于下发刷新/寻找模式等命令。 */
    val wardDeviceId: String = "",
)
