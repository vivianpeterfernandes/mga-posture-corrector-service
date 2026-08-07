package com.mygym.app.service;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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

    public SquatAnalysisService(VideoProcessingService videoProcessingService, PoseEstimationService poseEstimationService) {
        this.poseEstimationService = poseEstimationService;
    }

    public SquatAnalysisResult analyzeSquatVideo(MultipartFile file) throws Exception {
        Path cachedVideoPath = Files.createTempFile("squat_session_", ".mp4");
        file.transferTo(cachedVideoPath.toFile());
        File videoFile = cachedVideoPath.toFile();

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
                    counter++;
                    if (counter % frameInterval == 0) {
                        BufferedImage rawCanvas = converter.convert(frame);
                        if (rawCanvas == null) continue;

                        // Isolate, pass, and immediately discard image objects to let JVM garbage collection recycle bytes
                        Keypoints kp = poseEstimationService.predictKeypoints(rawCanvas);
                        processedFramesCount++;
                        totalVerticalLength += (kp.ankleY() - kp.shoulderY());

                        double kneeAngle = calculateJointAngle(kp.hipX(), kp.hipY(), kp.kneeX(), kp.kneeY(), kp.ankleX(), kp.ankleY());
                        double backAngle = calculateTorsoLean(kp.hipX(), kp.hipY(), kp.shoulderX(), kp.shoulderY());

                        // Track global absolute depth achievements across the video stream
                        if (kneeAngle < deepestKneeAngle && kneeAngle > 35.0) {
                            deepestKneeAngle = kneeAngle;
                        }
                        if (kneeAngle < 110.0 && backAngle > maxForwardLean) {
                            maxForwardLean = backAngle;
                        }

                        // STATE 1: Detecting the descent entry into the active squat zone (Below 110 degrees)
                        if (!inSquatZone && kneeAngle < 110.0) {
                            inSquatZone = true;
                            currentRepMinAngle = kneeAngle;
                            currentRepMaxLean = backAngle;
                        } 
                        // STATE 2: Trapped inside the squat loop tracking the turnaround trajectory metric
                        else if (inSquatZone) {
                            if (kneeAngle < currentRepMinAngle) {
                                currentRepMinAngle = kneeAngle; // Lock the absolute deepest point of this rep
                            }
                            if (backAngle > currentRepMaxLean) {
                                currentRepMaxLean = backAngle;  // Capture form warnings
                            }

                            // STATE 3: Clean exit validation boundary (Athlete must stand up past 145 degrees)
                            if (kneeAngle > 145.0) {
                                // Core Guardrail: Rep is only credited if they achieved true parallel depth (<= 105 degrees)
                                if (currentRepMinAngle <= 105.0) {
                                    repCounter++;
                                    bottomAngles.add(currentRepMinAngle);
                                }
                                // Reset rep-specific tracker contexts for the next repetition block loop
                                inSquatZone = false;
                                currentRepMinAngle = 180.0;
                            }
                        }
                    }
                }
                grabber.stop();
            }

            if (processedFramesCount == 0) {
                throw new IllegalArgumentException("Invalid clip asset input: No motion frames could be detected.");
            }

            double avgVerticalLength = totalVerticalLength / processedFramesCount;
            LOGGER.debug("Processed Frames: " + processedFramesCount + " | Calculated Avg Vertical Distance: " + avgVerticalLength);
            if (avgVerticalLength < 45.0) {
                throw new IllegalArgumentException(
                    "Exercise Rejected: The uploaded video does not appear to be a squat. " +
                    "Please upload a clear, side-profile video of a squat movement."
                );
            }

            String videoUrl = ""; // Bypasses video rendering pipeline completely to ensure high-speed processing

            StringBuilder feedback = new StringBuilder();
            feedback.append(String.format("Workout Completed! Tracked %d valid parallel repetitions. ", repCounter));
            feedback.append(String.format("Peak overall depth achieved: %d°. ", Math.round(deepestKneeAngle)));

            if (maxForwardLean > 40.0) 
                feedback.append(String.format("Form Warning: Excessive torso leaning detected (%d°). Keep your chest up to protect your lower back. ", Math.round(maxForwardLean)));
            return new SquatAnalysisResult(
                "SQUAT", repCounter, deepestKneeAngle, maxForwardLean, false, feedback.toString(), bottomAngles, videoUrl
            );
        } finally {
            Files.deleteIfExists(cachedVideoPath);
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

    private double calculateTorsoLean(double hX, double hY, double sX, double sY) {
        double deltaY = Math.abs(hY - sY);
        double deltaX = Math.abs(hX - sX);
        if (deltaY == 0) return 90.0;
        return Math.toDegrees(Math.atan(deltaX / deltaY)); 
    }
}
