package com.google.ar.core.examples.java.common.rendering;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.util.Log;
import android.util.Size;
import android.widget.LinearLayout;

import com.google.ar.core.Camera;
import com.google.ar.core.Frame;
import com.google.ar.core.Plane;
import com.google.ar.core.PointCloud;
import com.google.ar.core.TrackingState;
import com.google.ar.core.examples.java.common.ColoredAnchor;
import com.google.ar.core.examples.java.sharedcamera.SharedCameraActivity;
import com.google.ar.core.exceptions.CameraNotAvailableException;

import java.io.IOException;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class GlRenderer implements GLSurfaceView.Renderer {

	private static final String TAG = "GlRenderer";

	// Whether the GL surface has been created.
	private boolean surfaceCreated;
	private final BackgroundRenderer backgroundRenderer = new BackgroundRenderer();
	// Renderers, see hello_ar_java sample to learn more.
	private final ObjectRenderer virtualObject = new ObjectRenderer();
	private final ObjectRenderer virtualObjectShadow = new ObjectRenderer();
	private final PlaneRenderer planeRenderer = new PlaneRenderer();
	private final PointCloudRenderer pointCloudRenderer = new PointCloudRenderer();

	private final Context context;
	private boolean arMode;

	public GlRenderer(Context context) {
		this.context = context;
	}

	// GL surface created callback. Will be called on the GL thread.
	@Override
	public void onSurfaceCreated(GL10 gl, EGLConfig config) {
		surfaceCreated = true;

		// Set GL clear color to black.
		GLES20.glClearColor(0f, 0f, 0f, 1.0f);

		// Prepare the rendering objects. This involves reading shaders, so may throw an IOException.
		try {
			// Create the camera preview image texture. Used in non-AR and AR mode.
			backgroundRenderer.createOnGlThread(context);
			planeRenderer.createOnGlThread(context, "models/trigrid.png");
			pointCloudRenderer.createOnGlThread(context);

			virtualObject.createOnGlThread(context, "models/andy.obj", "models/andy.png");
			virtualObject.setMaterialProperties(0.0f, 2.0f, 0.5f, 6.0f);

			virtualObjectShadow.createOnGlThread(
					context, "models/andy_shadow.obj", "models/andy_shadow.png");
			virtualObjectShadow.setBlendMode(ObjectRenderer.BlendMode.Shadow);
			virtualObjectShadow.setMaterialProperties(1.0f, 0.0f, 0.0f, 1.0f);

			openCamera();
		} catch (IOException e) {
			Log.e(TAG, "Failed to read an asset file", e);
		}
	}

	// GL surface changed callback. Will be called on the GL thread.
	@Override
	public void onSurfaceChanged(GL10 gl, int width, int height) {
		GLES20.glViewport(0, 0, width, height);
		displayRotationHelper.onSurfaceChanged(width, height);

		runOnUiThread(
				() -> {
					// Adjust layout based on display orientation.
					imageTextLinearLayout.setOrientation(
							width > height ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
				});
	}

	// GL draw callback. Will be called each frame on the GL thread.
	@Override
	public void onDrawFrame(GL10 gl) {
		// Use the cGL clear color specified in onSurfaceCreated() to erase the GL surface.
		GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

		if (!shouldUpdateSurfaceTexture.get()) {
			// Not ready to draw.
			return;
		}

		// Handle display rotations.
		displayRotationHelper.updateSessionIfNeeded(sharedSession);

		try {
			if (arMode) {
				onDrawFrameARCore();
			} else {
				onDrawFrameCamera2();
			}
		} catch (Throwable t) {
			// Avoid crashing the application due to unhandled exceptions.
			Log.e(TAG, "Exception on the OpenGL thread", t);
		}
	}

	public boolean isSurfaceCreated() {
		return surfaceCreated;
	}

	public void suppressTimestampZeroRendering(boolean suppress) {
		backgroundRenderer.suppressTimestampZeroRendering(false);
	}

	public int getBackgroundTextureName() {
		return backgroundRenderer.getTextureId();
	}

	// Draw frame when in non-AR mode. Called on the GL thread.
	public void onDrawFrameCamera2() {
		SurfaceTexture texture = sharedCamera.getSurfaceTexture();

		// Ensure the surface is attached to the GL context.
		if (!isGlAttached) {
			texture.attachToGLContext(glRenderer.getBackgroundTextureName());
			isGlAttached = true;
		}

		// Update the surface.
		texture.updateTexImage();

		// Account for any difference between camera sensor orientation and display orientation.
		int rotationDegrees = displayRotationHelper.getCameraSensorToDisplayRotation(cameraId);

		// Determine size of the camera preview image.
		Size size = sharedSession.getCameraConfig().getTextureSize();

		// Determine aspect ratio of the output GL surface, accounting for the current display rotation
		// relative to the camera sensor orientation of the device.
		float displayAspectRatio =
				displayRotationHelper.getCameraSensorRelativeViewportAspectRatio(cameraId);

		// Render camera preview image to the GL surface.
		backgroundRenderer.draw(size.getWidth(), size.getHeight(), displayAspectRatio, rotationDegrees);
	}

	// Draw frame when in AR mode. Called on the GL thread.
	public void onDrawFrameARCore() throws CameraNotAvailableException {
		if (!arcoreActive) {
			// ARCore not yet active, so nothing to draw yet.
			return;
		}

		if (errorCreatingSession) {
			// Session not created, so nothing to draw.
			return;
		}

		// Perform ARCore per-frame update.
		Frame frame = sharedSession.update();
		Camera camera = frame.getCamera();

		// ARCore attached the surface to GL context using the texture ID we provided
		// in createCameraPreviewSession() via sharedSession.setCameraTextureName(…).
		isGlAttached = true;

		// Handle screen tap.
		handleTap(frame, camera);

		// If frame is ready, render camera preview image to the GL surface.
		backgroundRenderer.draw(frame);

		// Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
		trackingStateHelper.updateKeepScreenOnFlag(camera.getTrackingState());

		// If not tracking, don't draw 3D objects.
		if (camera.getTrackingState() == TrackingState.PAUSED) {
			return;
		}

		// Get projection matrix.
		float[] projmtx = new float[16];
		camera.getProjectionMatrix(projmtx, 0, 0.1f, 100.0f);

		// Get camera matrix and draw.
		float[] viewmtx = new float[16];
		camera.getViewMatrix(viewmtx, 0);

		// Compute lighting from average intensity of the image.
		// The first three components are color scaling factors.
		// The last one is the average pixel intensity in gamma space.
		final float[] colorCorrectionRgba = new float[4];
		frame.getLightEstimate().getColorCorrection(colorCorrectionRgba, 0);

		// Visualize tracked points.
		// Use try-with-resources to automatically release the point cloud.
		try (PointCloud pointCloud = frame.acquirePointCloud()) {
			pointCloudRenderer.update(pointCloud);
			pointCloudRenderer.draw(viewmtx, projmtx);
		}

		// If we detected any plane and snackbar is visible, then hide the snackbar.
		if (messageSnackbarHelper.isShowing()) {
			for (Plane plane : sharedSession.getAllTrackables(Plane.class)) {
				if (plane.getTrackingState() == TrackingState.TRACKING) {
					messageSnackbarHelper.hide(this);
					break;
				}
			}
		}

		// Visualize planes.
		planeRenderer.drawPlanes(
				sharedSession.getAllTrackables(Plane.class), camera.getDisplayOrientedPose(), projmtx);

		// Visualize anchors created by touch.
		float scaleFactor = 1.0f;
		for (ColoredAnchor coloredAnchor : anchors) {
			if (coloredAnchor.anchor.getTrackingState() != TrackingState.TRACKING) {
				continue;
			}
			// Get the current pose of an Anchor in world space. The Anchor pose is updated
			// during calls to sharedSession.update() as ARCore refines its estimate of the world.
			coloredAnchor.anchor.getPose().toMatrix(anchorMatrix, 0);

			// Update and draw the model and its shadow.
			virtualObject.updateModelMatrix(anchorMatrix, scaleFactor);
			virtualObjectShadow.updateModelMatrix(anchorMatrix, scaleFactor);
			virtualObject.draw(viewmtx, projmtx, colorCorrectionRgba, coloredAnchor.color);
			virtualObjectShadow.draw(viewmtx, projmtx, colorCorrectionRgba, coloredAnchor.color);
		}
	}
}
