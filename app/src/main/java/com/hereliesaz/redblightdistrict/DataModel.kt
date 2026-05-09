package com.hereliesaz.redblightdistrict

import java.util.Date

data class PropInfo(
    val address: String,
    val status: String,
    val noticeDate: String,
    val deadline: String,
    val graffitiScore: Double?,
    val streetviewThumbUrl: String?,
    val permitType: String?,
    val permitStatus: String?,
    val permitFiling: String?,
    val demolitionStatus: String?,
    val demolitionDate: String?,
    val landUseDesc: String?,
    val zoningClass: String?,
    val zoningDesc: String?,
    val hasStructure: Boolean?,
    val caseCount: Int?,
    val daysUnderBlight: Int?,
    val lastGrassCut: String?,
    val nextHearing: String?,
    val stage: String?,
    val permitCount365d: Int?,
    val permitTypesRecent: String?
)

data class MapItem(
    val caseno: String,
    val lat: Double,
    val lng: Double,
    val date: Date,
    val prop: PropInfo
)

data class UserPin(
    val id: String,
    val lat: Double,
    val lng: Double,
    var category: String,
    var title: String,
    var note: String,
    val created: String
)

data class PinCategory(
    val emoji: String,
    val name: String
)

data class DemolitionRow(
    val lat: Double,
    val lng: Double,
    val status: String?,
    val event_date: String?,
    val address: String?,
    val permit_no: String?
)

data class OsmFeatureRow(
    val lat: Double,
    val lng: Double,
    val category: String?,
    val name: String?,
    val id: String?,
    val tags_summary: String?
)
