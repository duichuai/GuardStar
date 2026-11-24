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
)
