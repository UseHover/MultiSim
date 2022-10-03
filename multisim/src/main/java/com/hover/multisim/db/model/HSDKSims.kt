/*
 * Copyright 2022 UseHover
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hover.multisim.db.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "hsdk_sims", indices = [Index(value = ["iccid"], unique = true)]
)
data class HSDKSims(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val slot_idx: Int,
    val sub_id: Int,
    val imei: String?,
    val state: Int,
    val imsi: String,
    val mcc: String,
    val mnc: String?,
    val iccid: String,
    val operator: String?,
    val operator_name: String?,
    val country_iso: String?,
    val is_roaming: Boolean,
    val network_code: String?,
    val network_name: String?,
    val network_country: String?,
    val network_type: Int?,
)
