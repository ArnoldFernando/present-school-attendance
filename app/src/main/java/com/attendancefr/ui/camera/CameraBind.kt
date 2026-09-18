package com.attendancefr.ui.camera

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun Context.awaitCameraProvider(): ProcessCameraProvider =
    suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                try {
                    if (cont.isActive) cont.resume(future.get())
                } catch (t: Throwable) {
                    if (cont.isActive) cont.resumeWithException(t)
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

/**
 * Bind [useCases] to the front camera, falling back to the back camera
 * on devices / emulators that do not expose a front lens.
 */
fun ProcessCameraProvider.bindWithFallback(
    lifecycleOwner: LifecycleOwner,
    vararg useCases: UseCase,
) {
    unbindAll()
    try {
        bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_FRONT_CAMERA,
            *useCases,
        )
    } catch (_: Exception) {
        unbindAll()
        bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            *useCases,
        )
    }
}
