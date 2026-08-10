package com.mygym.app.controller;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mygym.app.model.SquatAnalysisResult;
import com.mygym.app.service.SquatAnalysisService;

@RestController
@RequestMapping("/api/analyze")
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS})
public class SquatController {
	
    private static final Logger LOGGER = LoggerFactory.getLogger(SquatController.class);
    private final SquatAnalysisService squatAnalysisService;

    // 🎯 Decoupled Constructor Injection: Requires dependency ONLY on the Service tier
    public SquatController(SquatAnalysisService squatAnalysisService) {
        this.squatAnalysisService = squatAnalysisService;
    }

    /**
     * 🎯 STEP 1 ENDPOINT: Delegates upload URL generation to the service layer.
     * Accessible via GET: /api/analyze/request-url?fileName=squat_video.mp4
     */
    @GetMapping("/request-url")
    public ResponseEntity<Map<String, String>> getUploadUrl(@RequestParam("fileName") String fileName) {
        try {
            return ResponseEntity.ok(squatAnalysisService.getSecureUploadPath(fileName));
        } catch (Exception e) {
            LOGGER.error("❌ Failed to process request-url generation: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", "Failed to generate presigned upload route: " + e.getMessage()));
        }
    }

    /**
     * 🎯 STEP 2 ENDPOINT: Receives payload token and delegates orchestration loop to service tier.
     * Accessible via POST: /api/analyze/squat (Payload: {"fileKey": "unique_uuid_video.mp4"})
     */
    @PostMapping("/squat")
    public ResponseEntity<Map<String, Object>> verifySquat(@RequestBody Map<String, String> payload) {
        String fileKey = payload.get("fileKey");
        if (fileKey == null || fileKey.isBlank()) {
            return ResponseEntity.status(400).body(Map.of("success", false, "error", "Missing required payload parameter: fileKey"));
        }

        try {
            // 🎯 The thin delegation checkpoint hand-off line
            SquatAnalysisResult result = squatAnalysisService.processAndCleanupSquatVideo(fileKey);
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "detectedExercise", result.detectedExercise(),
                "repsCounted", result.totalReps(),
                "deepestKneeAngle", Math.round(result.deepestKneeAngle()),
                "maxForwardLeanAngle", Math.round(result.maxForwardLeanAngle()),
                "repAnglesBreakdown", result.repAngles(),
                "feedback", result.feedback()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(400).body(Map.of("success", false, "error", e.getLocalizedMessage()));
        } catch (Exception e) {
            LOGGER.error("❌ Deep orchestration tracking error encountered: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of("success", false, "error", "Internal server core error: " + e.getMessage()));
        }
    }
}
