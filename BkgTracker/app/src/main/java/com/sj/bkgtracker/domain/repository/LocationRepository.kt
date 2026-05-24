package com.sj.bkgtracker.domain.repository

import com.sj.bkgtracker.domain.model.LocationRecord

interface LocationRepository {
    suspend fun uploadBatch(records: List<LocationRecord>): Result<Unit>
}
