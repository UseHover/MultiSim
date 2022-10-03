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
package com.hover.multisim.db.dao

import androidx.room.Dao
import androidx.room.Query
import com.hover.multisim.db.model.HSDKSims
import kotlinx.coroutines.flow.Flow

@Dao
interface SimDao : BaseDao<HSDKSims> {

    @Query("SELECT * FROM hsdk_sims")
    fun getAll(): Flow<List<HSDKSims>> // return SimInfo

    @Query("SELECT * FROM hsdk_sims WHERE mcc =:mcc AND slot_idx != -1")
    fun getPresent(mcc: String): Flow<List<HSDKSims>> // return SimInfo

    @Query("SELECT * FROM hsdk_sims WHERE slot_idx =:slotIdx LIMIT 1")
    suspend fun get(slotIdx: Int): HSDKSims // return SimInfo

    @Query("SELECT * FROM hsdk_sims WHERE iccId =:iccId LIMIT 1")
    suspend fun loadBy(iccId: String): HSDKSims // return SimInfo
}
