/*
 * Copyright 2018 Google LLC
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

package com.google.ar.core.examples.java.sharedcamera;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.media.ImageReader;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.MotionEvent;
import android.view.Surface;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.google.ar.core.Anchor;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.Point;
import com.google.ar.core.Point.OrientationMode;
import com.google.ar.core.PointCloud;
import com.google.ar.core.Session;
import com.google.ar.core.SharedCamera;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingState;
import com.google.ar.core.examples.java.common.ColoredAnchor;
import com.google.ar.core.examples.java.common.helpers.CameraPermissionHelper;
import com.google.ar.core.examples.java.common.helpers.DisplayRotationHelper;
import com.google.ar.core.examples.java.common.helpers.FullScreenHelper;
import com.google.ar.core.examples.java.common.helpers.SnackbarHelper;
import com.google.ar.core.examples.java.common.helpers.TapHelper;
import com.google.ar.core.examples.java.common.helpers.TrackingStateHelper;
import com.google.ar.core.examples.java.common.rendering.BackgroundRenderer;
import com.google.ar.core.examples.java.common.rendering.GlRenderer;
import com.google.ar.core.examples.java.common.rendering.ObjectRenderer;
import com.google.ar.core.examples.java.common.rendering.ObjectRenderer.BlendMode;
import com.google.ar.core.examples.java.common.rendering.PlaneRenderer;
import com.google.ar.core.examples.java.common.rendering.PointCloudRenderer;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * This is a simple example that demonstrates how to use the Camera2 API while sharing camera access
 * with ARCore. An on-screen switch can be used to pause and resume ARCore. The app utilizes a
 * trivial sepia camera effect while ARCore is paused, and seamlessly hands camera capture request
 * control over to ARCore when it is running.
 *
 * <p>This app demonstrates:
 *
 * <ul>
 *   <li>Starting in AR or non-AR mode by setting the initial value of `arMode`
 *   <li>Toggling between non-AR and AR mode using an on screen switch
 *   <li>Pausing and resuming the app while in AR or non-AR mode
 *   <li>Requesting CAMERA_PERMISSION when app starts, and each time the app is resumed
 * </ul>
 */
public class SharedCameraActivity extends AppCompatActivity
    implements ImageReader.OnImageAvailableListener,
        SurfaceTexture.OnFrameAvailableListener {
  private static final String TAG = SharedCameraActivity.class.getSimpleName();

  // Whether the app is currently in AR mode. Initial value determines initial state.
  private boolean arMode = false;

  // Whether the surface texture has been attached to the GL context.
  boolean isGlAttached;

  // GL Surface used to draw camera preview image.
  private GLSurfaceView surfaceView;

  // Text view for displaying on screen status message.
  private TextView statusTextView;

  // Linear layout that contains preview image and status text.
  private LinearLayout imageTextLinearLayout;

  // ARCore session that supports camera sharing.
  private Session sharedSession;

  // Camera capture session. Used by both non-AR and AR modes.
  private CameraCaptureSession captureSession;

  // Reference to the camera system service.
  private CameraManager cameraManager;

  // A list of CaptureRequest keys that can cause delays when switching between AR and non-AR modes.
  private List<CaptureRequest.Key<?>> keysThatCanCauseCaptureDelaysWhenModified;

  // Camera device. Used by both non-AR and AR modes.
  private CameraDevice cameraDevice;

  // Looper handler thread.
  private HandlerThread backgroundThread;

  // Looper handler.
  private Handler backgroundHandler;

  // ARCore shared camera instance, obtained from ARCore session that supports sharing.
  private SharedCamera sharedCamera;

  // Camera ID for the camera used by ARCore.
  private String cameraId;

  // Ensure GL surface draws only occur when new frames are available.
  private final AtomicBoolean shouldUpdateSurfaceTexture = new AtomicBoolean(false);

  // Whether ARCore is currently active.
  private boolean arcoreActive;

  /**
   * Whether an error was thrown during session creation.
   */
  private boolean errorCreatingSession = false;

  // Camera preview capture request builder
  private CaptureRequest.Builder previewCaptureRequestBuilder;

  // Image reader that continuously processes CPU images.
  private ImageReader cpuImageReader;

  // Total number of CPU images processed.
  private int cpuImagesProcessed;

  // Various helper classes, see hello_ar_java sample to learn more.
  private final SnackbarHelper messageSnackbarHelper = new SnackbarHelper();
  private DisplayRotationHelper displayRotationHelper;
  private final TrackingStateHelper trackingStateHelper = new TrackingStateHelper(this);
  private TapHelper tapHelper;

  // Temporary matrix allocated here to reduce number of allocations for each frame.
  private final float[] anchorMatrix = new float[16];
  private static final float[] DEFAULT_COLOR = new float[] {0f, 0f, 0f, 0f};

  // Anchors created from taps, see hello_ar_java sample to learn more.
  private final ArrayList<ColoredAnchor> anchors = new ArrayList<>();

  // Required for test run.
  private static final Short AUTOMATOR_DEFAULT = 0;
  private static final String AUTOMATOR_KEY = "automator";
  private final AtomicBoolean automatorRun = new AtomicBoolean(false);

  // Prevent any changes to camera capture session after CameraManager.openCamera() is called, but
  // before camera device becomes active.
  private boolean captureSessionChangesPossible = true;

  // A check mechanism to ensure that the camera closed properly so that the app can safely exit.
  private final ConditionVariable safeToExitApp = new ConditionVariable();

  private GlRenderer glRenderer;

  // Camera device state callback.
  private final CameraDevice.StateCallback cameraDeviceCallback =
      new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
          Log.d(TAG, "Camera device ID " + cameraDevice.getId() + " opened.");
          SharedCameraActivity.this.cameraDevice = cameraDevice;
          createCameraPreviewSession();
        }

        @Override
        public void onClosed(@NonNull CameraDevice cameraDevice) {
          Log.d(TAG, "Camera device ID " + cameraDevice.getId() + " closed.");
          SharedCameraActivity.this.cameraDevice = null;
          safeToExitApp.open();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
          Log.w(TAG, "Camera device ID " + cameraDevice.getId() + " disconnected.");
          cameraDevice.close();
          SharedCameraActivity.this.cameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
          Log.e(TAG, "Camera device ID " + cameraDevice.getId() + " error " + error);
          cameraDevice.close();
          SharedCameraActivity.this.cameraDevice = null;
          // Fatal error. Quit application.
          finish();
        }
      };

  // Repeating camera capture session state callback.
  CameraCaptureSession.StateCallback cameraSessionStateCallback =
      new CameraCaptureSession.StateCallback() {

        // Called when the camera capture session is first configured after the app
        // is initialized, and again each time the activity is resumed.
        @Override
        public void onConfigured(@NonNull CameraCaptureSession session) {
          Log.d(TAG, "Camera capture session configured.");
          captureSession = session;
          if (arMode) {
            setRepeatingCaptureRequest();
            // Note, resumeARCore() must be called in onActive(), not here.
          } else {
            // Calls `setRepeatingCaptureRequest()`.
            resumeCamera2();
          }
        }

        @Override
        public void onSurfacePrepared(
            @NonNull CameraCaptureSession session, @NonNull Surface surface) {
          Log.d(TAG, "Camera capture surface prepared.");
        }

        @Override
        public void onReady(@NonNull CameraCaptureSession session) {
          Log.d(TAG, "Camera capture session ready.");
        }

        @Override
        public void onActive(@NonNull CameraCaptureSession session) {
          Log.d(TAG, "Camera capture session active.");
          if (arMode && !arcoreActive) {
            resumeARCore();
          }
          synchronized (SharedCameraActivity.this) {
            captureSessionChangesPossible = true;
            SharedCameraActivity.this.notify();
          }
          updateSnackbarMessage();
        }

        @Override
        public void onCaptureQueueEmpty(@NonNull CameraCaptureSession session) {
          Log.w(TAG, "Camera capture queue empty.");
        }

        @Override
        public void onClosed(@NonNull CameraCaptureSession session) {
          Log.d(TAG, "Camera capture session closed.");
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
          Log.e(TAG, "Failed to configure camera capture session.");
        }
      };

  // Repeating camera capture session capture callback.
  private final CameraCaptureSession.CaptureCallback cameraCaptureCallback =
      new CameraCaptureSession.CaptureCallback() {

        @Override
        public void onCaptureCompleted(
            @NonNull CameraCaptureSession session,
            @NonNull CaptureRequest request,
            @NonNull TotalCaptureResult result) {
          shouldUpdateSurfaceTexture.set(true);
        }

        @Override
        public void onCaptureBufferLost(
            @NonNull CameraCaptureSession session,
            @NonNull CaptureRequest request,
            @NonNull Surface target,
            long frameNumber) {
          Log.e(TAG, "onCaptureBufferLost: " + frameNumber);
        }

        @Override
        public void onCaptureFailed(
            @NonNull CameraCaptureSession session,
            @NonNull CaptureRequest request,
            @NonNull CaptureFailure failure) {
          Log.e(TAG, "onCaptureFailed: " + failure.getFrameNumber() + " " + failure.getReason());
        }

        @Override
        public void onCaptureSequenceAborted(
            @NonNull CameraCaptureSession session, int sequenceId) {
          Log.e(TAG, "onCaptureSequenceAborted: " + sequenceId + " " + session);
        }
      };

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    Bundle extraBundle = getIntent().getExtras();
    if (extraBundle != null && 1 == extraBundle.getShort(AUTOMATOR_KEY, AUTOMATOR_DEFAULT)) {
      automatorRun.set(true);
    }

    glRenderer = new GlRenderer(getApplicationContext());
    // GL surface view that renders camera preview image.
    surfaceView = findViewById(R.id.glsurfaceview);
    surfaceView.setPreserveEGLContextOnPause(true);
    surfaceView.setEGLContextClientVersion(2);
    surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
    surfaceView.setRenderer(glRenderer);
    surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

    // Helpers, see hello_ar_java sample to learn more.
    displayRotationHelper = new DisplayRotationHelper(this);
    tapHelper = new TapHelper(this);
    surfaceView.setOnTouchListener(tapHelper);

    imageTextLinearLayout = findViewById(R.id.image_text_layout);
    statusTextView = findViewById(R.id.text_view);

    // Switch to allow pausing and resuming of ARCore.
    Switch arcoreSwitch = findViewById(R.id.arcore_switch);
    // Ensure initial switch position is set based on initial value of `arMode` variable.
    arcoreSwitch.setChecked(arMode);
    arcoreSwitch.setOnCheckedChangeListener(
        (view, checked) -> {
          Log.i(TAG, "Switching to " + (checked ? "AR" : "non-AR") + " mode.");
          if (checked) {
            arMode = true;
            resumeARCore();
          } else {
            arMode = false;
            pauseARCore();
            resumeCamera2();
          }
          updateSnackbarMessage();
        });

    messageSnackbarHelper.setMaxLines(4);
    updateSnackbarMessage();
  }

  @Override
  protected void onDestroy() {
    if (sharedSession != null) {
      // Explicitly close ARCore Session to release native resources.
      // Review the API reference for important considerations before calling close() in apps with
      // more complicated lifecycle requirements:
      // https://developers.google.com/ar/reference/java/arcore/reference/com/google/ar/core/Session#close()
      sharedSession.close();
      sharedSession = null;
    }

    super.onDestroy();
  }

  private synchronized void waitUntilCameraCaptureSessionIsActive() {
    while (!captureSessionChangesPossible) {
      try {
        this.wait();
      } catch (InterruptedException e) {
        Log.e(TAG, "Unable to wait for a safe time to make changes to the capture session", e);
      }
    }
  }

  @Override
  protected void onResume() {
    super.onResume();
    waitUntilCameraCaptureSessionIsActive();
    startBackgroundThread();
    surfaceView.onResume();

    // When the activity starts and resumes for the first time, openCamera() will be called
    // from onSurfaceCreated(). In subsequent resumes we call openCamera() here.
    if (glRenderer.isSurfaceCreated()) {
      openCamera();
    }

    displayRotationHelper.onResume();
  }

  @Override
  public void onPause() {
    shouldUpdateSurfaceTexture.set(false);
    surfaceView.onPause();
    waitUntilCameraCaptureSessionIsActive();
    displayRotationHelper.onPause();
    if (arMode) {
      pauseARCore();
    }
    closeCamera();
    stopBackgroundThread();
    super.onPause();
  }

  private void resumeCamera2() {
    setRepeatingCaptureRequest();
    sharedCamera.getSurfaceTexture().setOnFrameAvailableListener(this);
  }

  private void resumeARCore() {
    // Ensure that session is valid before triggering ARCore resume. Handles the case where the user
    // manually uninstalls ARCore while the app is paused and then resumes.
    if (sharedSession == null) {
      return;
    }

    if (!arcoreActive) {
      try {
        // To avoid flicker when resuming ARCore mode inform the renderer to not suppress rendering
        // of the frames with zero timestamp.
        glRenderer.suppressTimestampZeroRendering(false);
        // Resume ARCore.
        sharedSession.resume();
        arcoreActive = true;
        updateSnackbarMessage();

        // Set capture session callback while in AR mode.
        sharedCamera.setCaptureCallback(cameraCaptureCallback, backgroundHandler);
      } catch (CameraNotAvailableException e) {
        Log.e(TAG, "Failed to resume ARCore session", e);
        return;
      }
    }
  }

  private void pauseARCore() {
    if (arcoreActive) {
      // Pause ARCore.
      sharedSession.pause();
      arcoreActive = false;
      updateSnackbarMessage();
    }
  }

  private void updateSnackbarMessage() {
    messageSnackbarHelper.showMessage(
        this,
        arcoreActive
            ? "ARCore is active.\nSearch for plane, then tap to place a 3D model."
            : "ARCore is paused.\nCamera effects enabled.");
  }

  // Called when starting non-AR mode or switching to non-AR mode.
  // Also called when app starts in AR mode, or resumes in AR mode.
  private void setRepeatingCaptureRequest() {
    try {
      setCameraEffects(previewCaptureRequestBuilder);

      captureSession.setRepeatingRequest(
          previewCaptureRequestBuilder.build(), cameraCaptureCallback, backgroundHandler);
    } catch (CameraAccessException e) {
      Log.e(TAG, "Failed to set repeating request", e);
    }
  }

  private void createCameraPreviewSession() {
    try {
      // Note that isGlAttached will be set to true in AR mode in onDrawFrame().
      sharedSession.setCameraTextureName(glRenderer.getBackgroundTextureName());
      sharedCamera.getSurfaceTexture().setOnFrameAvailableListener(this);

      // Create an ARCore compatible capture request using `TEMPLATE_RECORD`.
      previewCaptureRequestBuilder =
          cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);

      // Build surfaces list, starting with ARCore provided surfaces.
      List<Surface> surfaceList = sharedCamera.getArCoreSurfaces();

      // Add a CPU image reader surface. On devices that don't support CPU image access, the image
      // may arrive significantly later, or not arrive at all.
      surfaceList.add(cpuImageReader.getSurface());

      // Surface list should now contain three surfaces:
      // 0. sharedCamera.getSurfaceTexture()
      // 1. …
      // 2. cpuImageReader.getSurface()

      // Add ARCore surfaces and CPU image surface targets.
      for (Surface surface : surfaceList) {
        previewCaptureRequestBuilder.addTarget(surface);
      }

      // Wrap our callback in a shared camera callback.
      CameraCaptureSession.StateCallback wrappedCallback =
          sharedCamera.createARSessionStateCallback(cameraSessionStateCallback, backgroundHandler);

      // Create camera capture session for camera preview using ARCore wrapped callback.
      cameraDevice.createCaptureSession(surfaceList, wrappedCallback, backgroundHandler);
    } catch (CameraAccessException e) {
      Log.e(TAG, "CameraAccessException", e);
    }
  }

  // Start background handler thread, used to run callbacks without blocking UI thread.
  private void startBackgroundThread() {
    backgroundThread = new HandlerThread("sharedCameraBackground");
    backgroundThread.start();
    backgroundHandler = new Handler(backgroundThread.getLooper());
  }

  // Stop background handler thread.
  private void stopBackgroundThread() {
    if (backgroundThread != null) {
      backgroundThread.quitSafely();
      try {
        backgroundThread.join();
        backgroundThread = null;
        backgroundHandler = null;
      } catch (InterruptedException e) {
        Log.e(TAG, "Interrupted while trying to join background handler thread", e);
      }
    }
  }

  // Perform various checks, then open camera device and create CPU image reader.
  private void openCamera() {
    // Don't open camera if already opened.
    if (cameraDevice != null) {
      return;
    }

    // Verify CAMERA_PERMISSION has been granted.
    if (!CameraPermissionHelper.hasCameraPermission(this)) {
      CameraPermissionHelper.requestCameraPermission(this);
      return;
    }

    // Make sure that ARCore is installed, up to date, and supported on this device.
    if (!isARCoreSupportedAndUpToDate()) {
      return;
    }

    if (sharedSession == null) {
      try {
        // Create ARCore session that supports camera sharing.
        sharedSession = new Session(this, EnumSet.of(Session.Feature.SHARED_CAMERA));
      } catch (Exception e) {
        errorCreatingSession = true;
        messageSnackbarHelper.showError(
            this, "Failed to create ARCore session that supports camera sharing");
        Log.e(TAG, "Failed to create ARCore session that supports camera sharing", e);
        return;
      }

      errorCreatingSession = false;

      // Enable auto focus mode while ARCore is running.
      Config config = sharedSession.getConfig();
      config.setFocusMode(Config.FocusMode.AUTO);
      sharedSession.configure(config);
    }

    // Store the ARCore shared camera reference.
    sharedCamera = sharedSession.getSharedCamera();

    // Store the ID of the camera used by ARCore.
    cameraId = sharedSession.getCameraConfig().getCameraId();

    // Use the currently configured CPU image size.
    Size desiredCpuImageSize = sharedSession.getCameraConfig().getImageSize();
    cpuImageReader =
        ImageReader.newInstance(
            desiredCpuImageSize.getWidth(),
            desiredCpuImageSize.getHeight(),
            ImageFormat.YUV_420_888,
            2);
    cpuImageReader.setOnImageAvailableListener(this, backgroundHandler);

    // When ARCore is running, make sure it also updates our CPU image surface.
    sharedCamera.setAppSurfaces(this.cameraId, Arrays.asList(cpuImageReader.getSurface()));

    try {

      // Wrap our callback in a shared camera callback.
      CameraDevice.StateCallback wrappedCallback =
          sharedCamera.createARDeviceStateCallback(cameraDeviceCallback, backgroundHandler);

      // Store a reference to the camera system service.
      cameraManager = (CameraManager) this.getSystemService(Context.CAMERA_SERVICE);

      // Get the characteristics for the ARCore camera.
      CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(this.cameraId);

      // On Android P and later, get list of keys that are difficult to apply per-frame and can
      // result in unexpected delays when modified during the capture session lifetime.
      if (Build.VERSION.SDK_INT >= 28) {
        keysThatCanCauseCaptureDelaysWhenModified = characteristics.getAvailableSessionKeys();
        if (keysThatCanCauseCaptureDelaysWhenModified == null) {
          // Initialize the list to an empty list if getAvailableSessionKeys() returns null.
          keysThatCanCauseCaptureDelaysWhenModified = new ArrayList<>();
        }
      }

      // Prevent app crashes due to quick operations on camera open / close by waiting for the
      // capture session's onActive() callback to be triggered.
      captureSessionChangesPossible = false;

      // Open the camera device using the ARCore wrapped callback.
      cameraManager.openCamera(cameraId, wrappedCallback, backgroundHandler);
    } catch (CameraAccessException | IllegalArgumentException | SecurityException e) {
      Log.e(TAG, "Failed to open camera", e);
    }
  }

  private <T> boolean checkIfKeyCanCauseDelay(CaptureRequest.Key<T> key) {
    if (Build.VERSION.SDK_INT >= 28) {
      // On Android P and later, return true if key is difficult to apply per-frame.
      return keysThatCanCauseCaptureDelaysWhenModified.contains(key);
    } else {
      // On earlier Android versions, log a warning since there is no API to determine whether
      // the key is difficult to apply per-frame. Certain keys such as CONTROL_AE_TARGET_FPS_RANGE
      // are known to cause a noticeable delay on certain devices.
      // If avoiding unexpected capture delays when switching between non-AR and AR modes is
      // important, verify the runtime behavior on each pre-Android P device on which the app will
      // be distributed. Note that this device-specific runtime behavior may change when the
      // device's operating system is updated.
      Log.w(
          TAG,
          "Changing "
              + key
              + " may cause a noticeable capture delay. Please verify actual runtime behavior on"
              + " specific pre-Android P devices that this app will be distributed on.");
      // Allow the change since we're unable to determine whether it can cause unexpected delays.
      return false;
    }
  }

  // If possible, apply effect in non-AR mode, to help visually distinguish between from AR mode.
  private void setCameraEffects(CaptureRequest.Builder captureBuilder) {
    if (checkIfKeyCanCauseDelay(CaptureRequest.CONTROL_EFFECT_MODE)) {
      Log.w(TAG, "Not setting CONTROL_EFFECT_MODE since it can cause delays between transitions.");
    } else {
      Log.d(TAG, "Setting CONTROL_EFFECT_MODE to SEPIA in non-AR mode.");
      captureBuilder.set(
          CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_SEPIA);
    }
  }

  // Close the camera device.
  private void closeCamera() {
    if (captureSession != null) {
      captureSession.close();
      captureSession = null;
    }
    if (cameraDevice != null) {
      waitUntilCameraCaptureSessionIsActive();
      safeToExitApp.close();
      cameraDevice.close();
      safeToExitApp.block();
    }
    if (cpuImageReader != null) {
      cpuImageReader.close();
      cpuImageReader = null;
    }
  }

  // Surface texture on frame available callback, used only in non-AR mode.
  @Override
  public void onFrameAvailable(SurfaceTexture surfaceTexture) {
    // Log.d(TAG, "onFrameAvailable()");
  }

  // CPU image reader callback.
  @Override
  public void onImageAvailable(ImageReader imageReader) {
    Image image = imageReader.acquireLatestImage();
    if (image == null) {
      Log.w(TAG, "onImageAvailable: Skipping null image.");
      return;
    }

    image.close();
    cpuImagesProcessed++;

    // Reduce the screen update to once every two seconds with 30fps if running as automated test.
    if (!automatorRun.get() || (automatorRun.get() && cpuImagesProcessed % 60 == 0)) {
      runOnUiThread(
          () ->
              statusTextView.setText(
                  "CPU images processed: "
                      + cpuImagesProcessed
                      + "\n\nMode: "
                      + (arMode ? "AR" : "non-AR")
                      + " \nARCore active: "
                      + arcoreActive
                      + " \nShould update surface texture: "
                      + shouldUpdateSurfaceTexture.get()));
    }
  }

  // Android permission request callback.
  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(requestCode, permissions, results);
    if (!CameraPermissionHelper.hasCameraPermission(this)) {
      Toast.makeText(
              getApplicationContext(),
              "Camera permission is needed to run this application",
              Toast.LENGTH_LONG)
          .show();
      if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
        // Permission denied with checking "Do not ask again".
        CameraPermissionHelper.launchPermissionSettings(this);
      }
      finish();
    }
  }

  // Android focus change callback.
  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus);
  }

  // Handle only one tap per frame, as taps are usually low frequency compared to frame rate.
  private void handleTap(Frame frame, Camera camera) {
    MotionEvent tap = tapHelper.poll();
    if (tap != null && camera.getTrackingState() == TrackingState.TRACKING) {
      for (HitResult hit : frame.hitTest(tap)) {
        // Check if any plane was hit, and if it was hit inside the plane polygon
        Trackable trackable = hit.getTrackable();
        // Creates an anchor if a plane or an oriented point was hit.
        if ((trackable instanceof Plane
                && ((Plane) trackable).isPoseInPolygon(hit.getHitPose())
                && (PlaneRenderer.calculateDistanceToPlane(hit.getHitPose(), camera.getPose()) > 0))
            || (trackable instanceof Point
                && ((Point) trackable).getOrientationMode()
                    == OrientationMode.ESTIMATED_SURFACE_NORMAL)) {
          // Hits are sorted by depth. Consider only closest hit on a plane or oriented point.
          // Cap the number of objects created. This avoids overloading both the
          // rendering system and ARCore.
          if (anchors.size() >= 20) {
            anchors.get(0).anchor.detach();
            anchors.remove(0);
          }

          // Assign a color to the object for rendering based on the trackable type
          // this anchor attached to. For AR_TRACKABLE_POINT, it's blue color, and
          // for AR_TRACKABLE_PLANE, it's green color.
          float[] objColor;
          if (trackable instanceof Point) {
            objColor = new float[] {66.0f, 133.0f, 244.0f, 255.0f};
          } else if (trackable instanceof Plane) {
            objColor = new float[] {139.0f, 195.0f, 74.0f, 255.0f};
          } else {
            objColor = DEFAULT_COLOR;
          }

          // Adding an Anchor tells ARCore that it should track this position in
          // space. This anchor is created on the Plane to place the 3D model
          // in the correct position relative both to the world and to the plane.
          anchors.add(new ColoredAnchor(hit.createAnchor(), objColor));
          break;
        }
      }
    }
  }

  private boolean isARCoreSupportedAndUpToDate() {
    // Make sure ARCore is installed and supported on this device.
    ArCoreApk.Availability availability = ArCoreApk.getInstance().checkAvailability(this);
    switch (availability) {
      case SUPPORTED_INSTALLED:
        break;
      case SUPPORTED_APK_TOO_OLD:
      case SUPPORTED_NOT_INSTALLED:
        try {
          // Request ARCore installation or update if needed.
          ArCoreApk.InstallStatus installStatus =
              ArCoreApk.getInstance().requestInstall(this, /*userRequestedInstall=*/ true);
          switch (installStatus) {
            case INSTALL_REQUESTED:
              Log.e(TAG, "ARCore installation requested.");
              return false;
            case INSTALLED:
              break;
          }
        } catch (UnavailableException e) {
          Log.e(TAG, "ARCore not installed", e);
          runOnUiThread(
              () ->
                  Toast.makeText(
                          getApplicationContext(), "ARCore not installed\n" + e, Toast.LENGTH_LONG)
                      .show());
          finish();
          return false;
        }
        break;
      case UNKNOWN_ERROR:
      case UNKNOWN_CHECKING:
      case UNKNOWN_TIMED_OUT:
      case UNSUPPORTED_DEVICE_NOT_CAPABLE:
        Log.e(
            TAG,
            "ARCore is not supported on this device, ArCoreApk.checkAvailability() returned "
                + availability);
        runOnUiThread(
            () ->
                Toast.makeText(
                        getApplicationContext(),
                        "ARCore is not supported on this device, "
                            + "ArCoreApk.checkAvailability() returned "
                            + availability,
                        Toast.LENGTH_LONG)
                    .show());
        return false;
    }
    return true;
  }
}
