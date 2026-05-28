package com.sj.bkgtracker.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.sj.bkgtracker.domain.model.LocationRecord
import com.sj.bkgtracker.domain.repository.LocationRepository
import kotlinx.coroutines.tasks.await

class LocationRepositoryImpl : LocationRepository {

    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    override suspend fun uploadBatch(records: List<LocationRecord>): Result<Unit> {
        val user = auth.currentUser
            ?: return Result.failure(Exception("User not signed in"))

        return try {
            val batch = firestore.batch()
            val userDocRef = firestore
                .collection("locations")
                .document(user.uid)
            batch.set(
                userDocRef,
                mapOf("email" to (user.email ?: ""), "uid" to user.uid),
                com.google.firebase.firestore.SetOptions.merge()
            )

            val colRef = userDocRef.collection("records")

            records.forEach { r ->
                batch.set(
                    colRef.document(),
                    mapOf(
                        "lat"       to r.latitude,
                        "lon"       to r.longitude,
                        "timestamp" to r.timestampMs,
                        "accuracy"  to r.accuracyM,
                        "speed"     to r.speedKmh,
                        "altitude"  to r.altitudeM,
                        "bearing"   to r.bearingDeg,
                        "email"     to (user.email ?: "")
                    )
                )
            }

            batch.commit().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun requestExpressSync(): Result<Unit> {
        val user = auth.currentUser
            ?: return Result.failure(Exception("User not signed in"))

        return try {
            val expiresAt = System.currentTimeMillis() + 3_600_000L // 1 hour
            firestore.collection("express_sync")
                .document(user.uid)
                .set(
                    mapOf(
                        "requestedBy" to (user.email ?: ""),
                        "expiresAt" to expiresAt,
                        "requestedAt" to System.currentTimeMillis()
                    )
                ).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun stopExpressSync(): Result<Unit> {
        val user = auth.currentUser
            ?: return Result.failure(Exception("User not signed in"))

        return try {
            firestore.collection("express_sync")
                .document(user.uid)
                .set(
                    mapOf(
                        "action" to "stop",
                        "stoppedBy" to (user.email ?: ""),
                        "stoppedAt" to System.currentTimeMillis()
                    )
                ).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
