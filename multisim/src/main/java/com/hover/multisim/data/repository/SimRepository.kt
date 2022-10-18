package com.hover.multisim.data.repository

import com.hover.multisim.data.database.dao.SimDao
import com.hover.multisim.sim.SimInfo
import com.hover.multisim.sim.toSimInfo
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface SimRepository {
    fun getAllSims(): Flow<List<SimInfo>>
    fun getPresentSims(mcc: String): Flow<List<SimInfo>>
    suspend fun getSim(slotIdx: Int): SimInfo
    suspend fun loadBySim(iccId: String): SimInfo
}

class SimRepositoryImpl @Inject constructor(
    private val simDao: SimDao
) : SimRepository {

    override fun getAllSims(): Flow<List<SimInfo>> =
        simDao.getAllSims().map { hsdkSimsList -> hsdkSimsList.map { it.toSimInfo() } }

    override fun getPresentSims(mcc: String): Flow<List<SimInfo>> =
        simDao.getPresentSims(mcc).map { hsdkSimsList -> hsdkSimsList.map { it.toSimInfo() } }

    override suspend fun getSim(slotIdx: Int): SimInfo = simDao.getSim(slotIdx).toSimInfo()

    override suspend fun loadBySim(iccId: String): SimInfo = simDao.loadBySim(iccId).toSimInfo()
}
