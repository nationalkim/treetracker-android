package org.greenstand.android.TreeTracker.models

import com.google.gson.Gson
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.greenstand.android.TreeTracker.api.ObjectStorageClient
import org.greenstand.android.TreeTracker.api.models.requests.RegistrationRequest
import org.greenstand.android.TreeTracker.api.models.requests.UploadBundle
import org.greenstand.android.TreeTracker.database.TreeTrackerDAO
import org.greenstand.android.TreeTracker.usecases.UploadImageParams
import org.greenstand.android.TreeTracker.usecases.UploadImageUseCase
import org.greenstand.android.TreeTracker.utilities.md5
import timber.log.Timber

/**
 * Uploads all user data including the users photos
 * Deletes local photos once they are uploaded
 */
class PlanterUploader(
    private val dao: TreeTrackerDAO,
    private val uploadImageUseCase: UploadImageUseCase,
    private val gson: Gson,
    private val objectStorageClient: ObjectStorageClient,
    private val user: Planter
) {

    suspend fun uploadPlanters() {
        withContext(Dispatchers.IO) {
            uploadPlanterImages()
            uploadPlanterInfo()
//            deleteLocalImagesThatWereUploaded()
        }
    }

    private suspend fun uploadPlanterImages() {
        dao.getPlanterCheckInsToUpload()
            .filter { it.photoUrl == null && it.localPhotoPath != null }
            .forEach { planterCheckIn ->
                val imageUrl = uploadImageUseCase.execute(
                    UploadImageParams(
                        imagePath = planterCheckIn.localPhotoPath!!,
                        lat = planterCheckIn.latitude,
                        long = planterCheckIn.longitude
                    )
                )
                imageUrl?.let {
                    planterCheckIn.photoUrl = imageUrl
                    dao.updatePlanterCheckIn(planterCheckIn)
                }
            }
    }

    private suspend fun uploadPlanterInfo() {
        val planterInfoToUpload = dao.getAllPlanterInfoToUpload()

        Timber.tag(TAG)
            .d("Uploading Planter Info for ${planterInfoToUpload.size} planters")

        val registrationRequests = planterInfoToUpload
            .map { planterInfo ->
                // Find the image this user first took during registration
                // This image is the oldest image for PlanterCheckIn
                val registrationPhotoUrl = dao.getAllPlanterCheckInsForPlanterInfoId(planterInfo.id)
                    .minByOrNull { it.createdAt }
                    ?.photoUrl
                    ?: ""

                RegistrationRequest(
                    planterIdentifier = planterInfo.identifier,
                    firstName = planterInfo.firstName,
                    lastName = planterInfo.lastName,
                    organization = planterInfo.organization,
                    phone = planterInfo.phone,
                    email = planterInfo.email,
                    lat = planterInfo.latitude,
                    lon = planterInfo.longitude,
                    recordUuid = planterInfo.recordUuid,
                    imageUrl = registrationPhotoUrl
                )
            }

        val jsonBundle = gson.toJson(UploadBundle(registrations = registrationRequests))
        val bundleId = jsonBundle.md5() + "_registrations"
        val planterInfoIds = planterInfoToUpload.map { it.id }

        // Update the trees in DB with the bundleId
        dao.updatePlanterInfoBundleIds(planterInfoIds, bundleId)

        objectStorageClient.uploadBundle(jsonBundle, bundleId)

        dao.updatePlanterInfoUploadStatus(planterInfoIds, true)
    }

    private suspend fun deleteLocalImagesThatWereUploaded() {
        // Delete all local image files for registrations except for the currently logged in users photo...
        val loggedOutPlanterCheckIns = dao.getPlanterCheckInsToUpload()
            .filter {
                it.id != user.planterCheckinId &&
                        it.localPhotoPath != null && it.photoUrl != null
            }
            .sortedBy { it.createdAt }

        loggedOutPlanterCheckIns.mapNotNull { it.localPhotoPath }
            .forEach { localPhotoPath ->
            val photoFile = File(localPhotoPath)
            if (photoFile.exists()) {
                photoFile.delete()
            }
        }

        dao.removePlanterCheckInLocalImagePaths(loggedOutPlanterCheckIns.map { it.id })
    }

    companion object {
        private const val TAG = "PlanterUploader"
    }
}