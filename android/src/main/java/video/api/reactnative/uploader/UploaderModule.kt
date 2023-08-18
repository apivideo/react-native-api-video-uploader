package video.api.reactnative.uploader

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.work.WorkManager
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactMethod
import video.api.reactnative.uploader.extensions.observeTillItFinishes
import video.api.reactnative.uploader.extensions.showDialog
import video.api.uploader.VideosApi
import video.api.uploader.api.work.stores.VideosApiStore
import video.api.uploader.api.work.upload
import video.api.uploader.api.work.uploadWithUploadToken
import video.api.uploader.api.work.workers.AbstractUploadWorker
import java.io.File


class UploaderModule(private val reactContext: ReactApplicationContext) :
  UploaderModuleSpec(reactContext) {
  private var videosApi = VideosApi()
  private val handler = Handler(Looper.getMainLooper())
  private val workManager = WorkManager.getInstance(reactContext)
  private val permissionManager = PermissionManager(reactContext)

  init {
    initializeVideosApi()
  }

  override fun getName() = NAME

  private fun initializeVideosApi() {
    videosApi.apiClient.setSdkName(SDK_NAME, SDK_VERSION)
    VideosApiStore.initialize(videosApi)
  }

  @ReactMethod
  override fun setApplicationName(name: String, version: String) {
    videosApi.apiClient.setApplicationName(name, version)
  }

  @ReactMethod
  override fun setApiKey(apiKey: String?) {
    val chunkSize = videosApi.apiClient.uploadChunkSize
    videosApi = if (apiKey == null) {
      VideosApi(videosApi.apiClient.basePath)
    } else {
      VideosApi(apiKey, videosApi.apiClient.basePath)
    }
    videosApi.apiClient.uploadChunkSize = chunkSize

    initializeVideosApi()
  }

  @ReactMethod
  override fun setEnvironment(environment: String) {
    videosApi.apiClient.basePath = environment
  }

  @ReactMethod
  override fun setChunkSize(size: Double, promise: Promise) {
    try {
      videosApi.apiClient.uploadChunkSize = size.toLong()
      promise.resolve(videosApi.apiClient.uploadChunkSize.toInt())
    } catch (e: Exception) {
      promise.reject("failed_to_set_chunk_size", "Failed to set chunk size", e)
    }
  }

  @ReactMethod
  override fun setTimeout(timeout: Double) {
    val timeoutMs = (timeout * 1000).toInt()
    videosApi.apiClient.connectTimeout = timeoutMs
    videosApi.apiClient.readTimeout = timeoutMs
    videosApi.apiClient.writeTimeout = timeoutMs
  }

  @ReactMethod
  override fun uploadWithUploadToken(
    token: String,
    filePath: String,
    videoId: String?,
    promise: Promise
  ) {
    permissionManager.requestPermission(
      Utils.readPermission,
      onGranted = {
        uploadWithUploadTokenAndObserve(token, filePath, videoId, promise)
      },
      onShowPermissionRationale = { onRequiredPermissionLastTime ->
        (reactContext.currentActivity as AppCompatActivity).showDialog(
          R.string.read_permission_required,
          R.string.read_permission_required_message,
          android.R.string.ok,
          onPositiveButtonClick = { onRequiredPermissionLastTime() }
        )
      },
      onDenied = {
        promise.reject(
          "missing_permission",
          "Missing permission ${Utils.readPermission}"
        )
      }
    )
    return
  }

  private fun uploadWithUploadTokenAndObserve(
    token: String,
    filePath: String,
    videoId: String?,
    promise: Promise
  ) {
    try {
      val operationWithRequest = workManager.uploadWithUploadToken(token, File(filePath), videoId)
      val workInfoLiveData = workManager.getWorkInfoByIdLiveData(operationWithRequest.request.id)
      handler.post {
        workInfoLiveData.observeTillItFinishes(
          (reactContext.currentActivity as AppCompatActivity),
          onUploadEnqueued = {
            Log.d(TAG, "Upload with upload token enqueued")
          },
          onUploadRunning = {},
          onUploadSucceeded = { data ->
            promise.resolve(data.getString(AbstractUploadWorker.VIDEO_KEY)!!)
          },
          onUploadFailed = { data ->
            promise.reject(
              "upload_with_upload_token_failed",
              data.getString(AbstractUploadWorker.ERROR_KEY)
                ?: reactContext.getString(R.string.unknown_error)
            )
          },
          onUploadBlocked = {},
          onUploadCancelled = {
            promise.reject(
              "upload_with_upload_token_cancelled",
              reactContext.getString(R.string.upload_with_upload_token_cancelled)
            )
          },
          removeObserverAfterNull = false
        )
      }
    } catch (e: Exception) {
      promise.reject(
        "upload_with_upload_token_failed",
        reactContext.getString(R.string.upload_with_upload_token_failed),
        e
      )
    }
  }

  @ReactMethod
  override fun upload(videoId: String, filePath: String, promise: Promise) {
    permissionManager.requestPermission(
      Utils.readPermission,
      onGranted = {
        uploadAndObserve(videoId, filePath, promise)
      },
      onShowPermissionRationale = { onRequiredPermissionLastTime ->
        (reactContext.currentActivity as AppCompatActivity).showDialog(
          R.string.read_permission_required,
          R.string.read_permission_required_message,
          android.R.string.ok,
          onPositiveButtonClick = { onRequiredPermissionLastTime() }
        )
      },
      onDenied = {
        promise.reject(
          "missing_permission",
          "Missing permission ${Utils.readPermission}"
        )
      }
    )
    return
  }

  private fun uploadAndObserve(videoId: String, filePath: String, promise: Promise) {
    try {
      val operationWithRequest = workManager.upload(videoId, File(filePath))
      val workInfoLiveData = workManager.getWorkInfoByIdLiveData(operationWithRequest.request.id)
      handler.post {
        workInfoLiveData.observeTillItFinishes(
          (reactContext.currentActivity as AppCompatActivity),
          onUploadEnqueued = {
            Log.d(TAG, "Upload enqueued")
          },
          onUploadRunning = {},
          onUploadSucceeded = { data ->
            promise.resolve(data.getString(AbstractUploadWorker.VIDEO_KEY)!!)
          },
          onUploadFailed = { data ->
            promise.reject(
              "upload_failed", data.getString(AbstractUploadWorker.ERROR_KEY)
                ?: reactContext.getString(R.string.unknown_error)
            )
          },
          onUploadBlocked = {},
          onUploadCancelled = {
            promise.reject(
              "upload_cancelled",
              reactContext.getString(R.string.upload_cancel)
            )
          },
          removeObserverAfterNull = false
        )
      }
    } catch (e: Exception) {
      promise.reject("upload_failed", reactContext.getString(R.string.upload_failed), e)
    }
  }

  companion object {
    const val NAME = "ApiVideoUploader"
    const val TAG = "UploaderModule"

    const val SDK_NAME = "reactnative-uploader"
    const val SDK_VERSION = "1.1.0"
  }
}
