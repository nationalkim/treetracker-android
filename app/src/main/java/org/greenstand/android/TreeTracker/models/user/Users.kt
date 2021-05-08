package org.greenstand.android.TreeTracker.models

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.greenstand.android.TreeTracker.analytics.Analytics
import org.greenstand.android.TreeTracker.database.TreeTrackerDAO
import org.greenstand.android.TreeTracker.database.entity.PlanterCheckInEntity
import org.greenstand.android.TreeTracker.database.entity.PlanterInfoEntity
import org.greenstand.android.TreeTracker.models.user.User
import timber.log.Timber
import java.util.*

class Users(
    private val locationUpdateManager: LocationUpdateManager,
    private val dao: TreeTrackerDAO,
    private val analytics: Analytics
) {

    var currentSessionUser: User? = null
        private set

    suspend fun getUsers(): List<User> {
        val planterInfoList = dao.getAllPlanterInfo()
        val planterCheckIns = dao.getPlanterCheckInsById(planterInfoList.map { it.id })
        TODO()
    }

    suspend fun getUser(planterInfoId: Long): User? {
        return createUser(dao.getPlanterInfoById(planterInfoId), dao.getAllPlanterCheckInsForPlanterInfoId(planterInfoId).first())
    }

    suspend fun getPowerUser(): PlanterInfoEntity? = dao.getPowerUser()

    suspend fun createUser(
        firstName: String,
        lastName: String,
        organization: String?,
        phone: String?,
        email: String?,
        identifier: String,
        photoPath: String,
        isPowerUser: Boolean = false
    ) {
        withContext(Dispatchers.IO) {

            val location = locationUpdateManager.currentLocation
            val time = location?.time ?: System.currentTimeMillis()

            val entity = PlanterInfoEntity(
                identifier = identifier,
                firstName = firstName,
                lastName = lastName,
                organization = organization,
                phone = phone,
                email = email,
                longitude = location?.longitude ?: 0.0,
                latitude = location?.latitude ?: 0.0,
                createdAt = time,
                uploaded = false,
                recordUuid = UUID.randomUUID().toString(),
                isPowerUser = isPowerUser,
            )

            val userId = dao.insertPlanterInfo(entity).also {
                analytics.userInfoCreated(
                    phone = phone.orEmpty(),
                    email = email.orEmpty()
                )
            }

            startUserSession(
                localPhotoPath = photoPath,
                planterInfoId = userId,
            )
        }
    }

    suspend fun startUserSession(
        localPhotoPath: String,
        planterInfoId: Long
    ) {
        withContext(Dispatchers.IO) {

            val location = locationUpdateManager.currentLocation
            val time = location?.time ?: System.currentTimeMillis()

            val planterCheckInEntity = PlanterCheckInEntity(
                planterInfoId = planterInfoId,
                localPhotoPath = localPhotoPath,
                longitude = location?.longitude ?: 0.0,
                latitude = location?.latitude ?: 0.0,
                createdAt = time,
                photoUrl = null
            )

            dao.insertPlanterCheckIn(planterCheckInEntity)

            dao.getPlanterInfoById(planterInfoId)?.let { planterInfo ->
                currentSessionUser = createUser(planterInfo, planterCheckInEntity)
            } ?: Timber.e("Could not find planter info of id $planterInfoId")

            analytics.userCheckedIn()
        }
    }

    fun endUserSession() {
        currentSessionUser = null
    }

    private fun createUser(planterInfoEntity: PlanterInfoEntity, planterCheckInEntity: PlanterCheckInEntity) =
        User(
            id = planterInfoEntity.id,
            wallet = planterInfoEntity.identifier,
            firstName = planterInfoEntity.firstName,
            lastName = planterInfoEntity.lastName,
            photoPath = planterCheckInEntity.localPhotoPath ?: "",
            isPowerUser = planterInfoEntity.isPowerUser
        )
}