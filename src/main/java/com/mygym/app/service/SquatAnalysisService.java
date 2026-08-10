package com.mygym.app.service;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.mygym.app.model.Keypoints;
import com.mygym.app.model.SquatAnalysisResult;

@Service
public class SquatAnalysisService {

	private static final Logger LOGGER = LoggerFactory.getLogger(SquatAnalysisService.class);

	private final PoseEstimationService poseEstimationService;
	private final StorageService storageService; // 🎯 Added explicit constructor dependency tracking

	// 🎯 Spring Bean Constructor Injection matching all core dependent storage modules
	public SquatAnalysisService(PoseEstimationService poseEstimationService, StorageService storageService) {
		this.poseEstimationService = poseEstimationService;
		this.storageService = storageService;
	}

	/**
	 * 🎯 STEP 1 REFACTOR DELEGATION:
	 * Bridges the Controller to your Storage Service offline generation loop.
	 */
	public Map<String, String> getSecureUploadPath(String fileName) {
		return storageService.generateUploadUrl(fileName);
	}

	/**
	 * 🎯 STEP 2 REFACTOR ORCHESTRATION PIPELINE:
	 * Downloads, processes kinematics, deletes local container file, and clears Backblaze asynchronously.
	 */
	public SquatAnalysisResult processAndCleanupSquatVideo(String fileKey) throws Exception {
		File temporaryVideoFile = null;
		try {
			// 1. Ingest the file bytes down from your Backblaze B2 bucket straight onto disk scratchpad
			temporaryVideoFile = storageService.downloadFileFromStorage(fileKey);

			// 2. Perform the analytical frame kinematics computations loop 
			SquatAnalysisResult result = this.analyzeSquatVideoFile(temporaryVideoFile);

			// 3. Asynchronously trigger background cloud file deletion
			triggerAsynchronousCloudPurge(fileKey);

			return result;

		} catch (Exception e) {
			// Failsafe background cloud wipe trigger for broken, unparseable, or rejected run assets
			triggerAsynchronousCloudPurge(fileKey);
			throw e;
		} finally {
			// 4. Force synchronous immediate local disk scratchpad file cleanup (Railway OS layer)
			if (temporaryVideoFile != null && temporaryVideoFile.exists()) {
				boolean localDeleted = temporaryVideoFile.delete();
				if (localDeleted) {
					LOGGER.info("🧹 Cleaned local container video scratchpad trace inside service layer.");
				}
			}
		}
	}

	/**
	 * 🎯 NON-BLOCKING BACKGROUND WORKER:
	 * Dispatches a separate thread execution loop to wipe cloud assets without blocking the HTTP response.
	 */
	private void triggerAsynchronousCloudPurge(String fileKey) {
		CompletableFuture.runAsync(() -> {
			try {
				// Short breather block ensuring the file grabber completely frees channel stream pointers before wiping
				Thread.sleep(500); 
				storageService.deleteFileFromStorage(fileKey);
			} catch (Exception e) {
				LOGGER.error("🗑️ Background cloud package file purge failed for key [{}]: {}", fileKey, e.getMessage());
			}
		});
	}

	public SquatAnalysisResult analyzeSquatVideo(MultipartFile file) throws Exception {
		Path cachedVideoPath = Files.createTempFile("squat_session_", ".mp4");
		file.transferTo(cachedVideoPath.toFile());
		File videoFile = cachedVideoPath.toFile();

		try {
			return analyzeSquatVideoFile(videoFile);
		} finally {
			// Delete legacy multipart temp paths cleanly
			Files.deleteIfExists(cachedVideoPath);
		}
	}

	public SquatAnalysisResult analyzeSquatVideoFile(File videoFile) throws Exception {
		try {
			double totalVerticalLength = 0;
			long processedFramesCount = 0;

			List<Double> bottomAngles = new ArrayList<>();
			double deepestKneeAngle = 180.0;
			double maxForwardLean = 0.0;

			boolean inSquatZone = false;
			double currentRepMinAngle = 180.0;
			double currentRepMaxLean = 0.0;
			int repCounter = 0;

			try (org.bytedeco.javacv.FFmpegFrameGrabber grabber = new org.bytedeco.javacv.FFmpegFrameGrabber(videoFile);
					org.bytedeco.javacv.Java2DFrameConverter converter = new org.bytedeco.javacv.Java2DFrameConverter()) {

				grabber.setImageMode(org.bytedeco.javacv.FrameGrabber.ImageMode.COLOR);
				grabber.start();

				org.bytedeco.javacv.Frame frame;
				int counter = 0;
				int frameInterval = 3;

				while ((frame = grabber.grabImage()) != null) {
					try {
						counter++;
						if (counter % frameInterval == 0) {
							BufferedImage rawCanvas = converter.convert(frame);
							if (rawCanvas == null) continue;

							Keypoints kp = poseEstimationService.predictKeypoints(rawCanvas);
							processedFramesCount++;
							totalVerticalLength += (kp.ankleY() - kp.shoulderY());

							double kneeAngle = calculateJointAngle(kp.hipX(), kp.hipY(), kp.kneeX(), kp.kneeY(), kp.ankleX(), kp.ankleY());
							double backAngle = calculateTorsoLean(kp.hipX(), kp.hipY(), kp.shoulderX(), kp.shoulderY());

							if (kneeAngle < deepestKneeAngle && kneeAngle > 35.0) {
								deepestKneeAngle = kneeAngle;
							}
							if (kneeAngle < 110.0 && backAngle > maxForwardLean) {
								maxForwardLean = backAngle;
							}

							if (!inSquatZone && kneeAngle < 110.0) {
								inSquatZone = true;
								currentRepMinAngle = kneeAngle;
								currentRepMaxLean = backAngle;
							} 
							else if (inSquatZone) {
								if (kneeAngle < currentRepMinAngle) {
									currentRepMinAngle = kneeAngle;
								}
								if (backAngle > currentRepMaxLean) {
									currentRepMaxLean = backAngle;
								}

								if (kneeAngle > 145.0) {
									if (currentRepMinAngle <= 105.0) {
										repCounter++;
										bottomAngles.add(currentRepMinAngle);
									}
									inSquatZone = false;
									currentRepMinAngle = 180.0;
								}
							}
						}
					} finally {
						if (frame != null) {
							frame.close();
						}
					}
				}
				grabber.stop();
			}

			if (processedFramesCount == 0) {
				throw new IllegalArgumentException("Invalid clip asset input: No motion frames could be detected.");
			}

			double avgVerticalLength = totalVerticalLength / processedFramesCount;
			LOGGER.debug("Processed Frames: {} | Calculated Avg Vertical Distance: {}", processedFramesCount, avgVerticalLength);

			if (avgVerticalLength < 45.0) {
				throw new IllegalArgumentException(
						"Exercise Rejected: The uploaded video does not appear to be a squat. " +
								"Please upload a clear, side-profile video of a squat movement."
						);
			}

			String videoUrl = ""; 

			StringBuilder feedback = new StringBuilder();
			feedback.append(String.format("Workout Completed! Tracked %d valid parallel repetitions. ", repCounter));
			feedback.append(String.format("Peak overall depth achieved: %d°. ", Math.round(deepestKneeAngle)));

			if (maxForwardLean > 40.0) {
				feedback.append(String.format("Form Warning: Excessive torso leaning detected (%d°). Keep your chest up to protect your lower back. ", Math.round(maxForwardLean)));
			}
			return new SquatAnalysisResult(
					"SQUAT", repCounter, deepestKneeAngle, maxForwardLean, false, feedback.toString(), bottomAngles, videoUrl
					);
		} finally {
			System.gc();
			System.runFinalization();
		}
	}

	private double calculateJointAngle(double hX, double hY, double kX, double kY, double aX, double aY) {
		double a2 = Math.pow(kX - hX, 2) + Math.pow(kY - hY, 2);   
		double b2 = Math.pow(kX - aX, 2) + Math.pow(kY - aY, 2); 
		double c2 = Math.pow(aX - hX, 2) + Math.pow(aY - hY, 2); 
		double a = Math.sqrt(a2), b = Math.sqrt(b2);
		if (a == 0 || b == 0) return 180.0; 
		double cosKnee = (a2 + b2 - c2) / (2 * a * b);
		double interiorAngle = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, cosKnee))));
		return (interiorAngle < 90.0) ? 180.0 - interiorAngle : interiorAngle;
	}

	private double calculateTorsoLean(double hX, double hY, double sX, double sY) {double deltaY = Math.abs(hY - sY);double deltaX = Math.abs(hX - sX);if (deltaY == 0) return 90.0;return Math.toDegrees(Math.atan(deltaX / deltaY));}}