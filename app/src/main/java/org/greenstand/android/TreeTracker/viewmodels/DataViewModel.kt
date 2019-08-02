package org.greenstand.android.TreeTracker.viewmodels

import android.content.Context
import android.content.IntentFilter
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.greenstand.android.TreeTracker.R
import org.greenstand.android.TreeTracker.api.RetrofitApi
import org.greenstand.android.TreeTracker.background.SyncBroadcastReceiver
import org.greenstand.android.TreeTracker.background.SyncService
import org.greenstand.android.TreeTracker.database.v2.TreeTrackerDAO
import org.greenstand.android.TreeTracker.usecases.SyncTreeParams
import org.greenstand.android.TreeTracker.usecases.SyncTreeUseCase
import org.greenstand.android.TreeTracker.usecases.UploadPlanterParams
import org.greenstand.android.TreeTracker.usecases.UploadPlanterUseCase
import timber.log.Timber


class DataViewModel(private val syncTreeUseCase: SyncTreeUseCase,
                    private val uploadPlanterDetailsUseCase: UploadPlanterUseCase,
                    private val api: RetrofitApi,
                    private val dao: TreeTrackerDAO,
                    private val syncDataUseCase: SyncDataUseCase,
                    private val localBroadcastManager: LocalBroadcastManager,
                    private val context: Context) : CoroutineViewModel() {

    private val treeInfoLiveData = MutableLiveData<TreeData>()
    private val toastLiveData = MutableLiveData<Int>()
    private val isSyncingLiveData = MutableLiveData<Boolean>()

    val treeData: LiveData<TreeData> = treeInfoLiveData
    val toasts: LiveData<Int> = toastLiveData
    val isSyncing: LiveData<Boolean> = isSyncingLiveData

    private val broadcastReceiver = SyncBroadcastReceiver()

    private var currentJob: Job? = null

    init {
        updateData()

        broadcastReceiver.onDataReceived = {
            updateData()
        }

        localBroadcastManager.registerReceiver(broadcastReceiver,
                                               IntentFilter(SyncService.ACTION_ID))
    }

    fun sync() {
        launch {
            if (currentJob == null) {
                val treesToSync =
                    withContext(Dispatchers.IO) { dao.getNonUploadedTreeCaptureCount() }
                when (treesToSync) {
                    0 -> {
                        toastLiveData.value = R.string.nothing_to_sync
                    }
                    else -> {
                        toastLiveData.value = R.string.sync_started
                        startDataSynchronization()
                    }
                }
            } else {
                currentJob?.cancel()
                currentJob = null
                isSyncingLiveData.value = false
                toastLiveData.value = R.string.sync_stopped
            }
        }
    }

    private fun updateData() {
        launch(Dispatchers.IO) {

            val syncedTreeCount = dao.getUploadedTreeCaptureCount()
            val notSyncedTreeCount = dao.getNonUploadedTreeCaptureCount()
            val treeCount = syncedTreeCount + notSyncedTreeCount

            withContext(Dispatchers.Main) {
                treeInfoLiveData.value = TreeData(totalTrees = treeCount,
                                                  treesToSync = notSyncedTreeCount,
                                                  treesSynced = syncedTreeCount)
            }
        }
    }

    private fun startDataSynchronization() {
        isSyncingLiveData.value = true
        currentJob = launch {

//            syncDataUseCase.execute(Unit)

            SyncService.enqueueWork(context)

//            val isAuthenticated = withContext(Dispatchers.IO) { api.authenticateDevice() }
//
//            if (!isAuthenticated) {
//                toastLiveData.value = R.string.sync_failed
//                isSyncingLiveData.value = false
//                return@launch
//            }
//
//            withContext(Dispatchers.IO) {
//
//                uploadPlanters()
//
//                uploadNewTrees()
//            }

            toastLiveData.value = R.string.sync_successful
            isSyncingLiveData.value = false
        }
    }

    private suspend fun uploadPlanters() {
        // Upload all user registration data that hasn't been uploaded yet
        val planterInfoToUploadList = dao.getAllPlanterInfo()

        Timber.tag("DataViewModel").d("Uploading Planter Info for ${planterInfoToUploadList.size} planters")

        planterInfoToUploadList.forEach {
            runCatching {
                withContext(Dispatchers.IO) { uploadPlanterDetailsUseCase.execute(UploadPlanterParams(planterInfoId = it.id)) }
            }
        }
    }

    private suspend fun uploadNewTrees() {

        val treeIdList = dao.getAllTreeCaptureIdsToUpload()

        Timber.tag("DataViewModel").d("Uploading ${treeIdList.size} trees")

        treeIdList.onEach {

            try {
                withContext(Dispatchers.IO) { syncTreeUseCase.execute(SyncTreeParams(treeId = it)) }
            } catch (e: Exception) {
                Timber.e("NewTree upload failed")
            }

            updateData()
        }
    }

    override fun onCleared() {
        stopSyncing()
        localBroadcastManager.unregisterReceiver(broadcastReceiver)
    }

    fun stopSyncing() {
        currentJob?.cancel()
        currentJob = null
        isSyncingLiveData.value = false
    }

}

data class TreeData(val treesSynced: Int,
                    val treesToSync: Int,
                    val totalTrees: Int)