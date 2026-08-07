package com.mygym.app.controller;

import java.io.File;
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
import com.mygym.app.service.StorageService;

@RestController
@RequestMapping({"/api/analyze", "/api/analyze/"})
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS})
public class SquatController {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(SquatController.class);

    private final SquatAnalysisService squatAnalysisService;
    private final StorageService storageService;

    // 🎯 Constructor Injection mapping including your new Storage Service bean
    public SquatController(SquatAnalysisService squatAnalysisService, StorageService storageService) {
        this.squatAnalysisService = squatAnalysisService;
        this.storageService = storageService;
    }

    /**
     * 🎯 STEP 1 ENDPOINT: Generates a lightweight, secure upload path signature for Flutter.
     * Accessible via GET: /api/analyze/request-url?fileName=squat_video.mp4
     */
    @GetMapping("/request-url")
    public ResponseEntity<Map<String, String>> getUploadUrl(@RequestParam("fileName") String fileName) {
        try {
            return ResponseEntity.ok(storageService.generateUploadUrl(fileName));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "Failed to generate presigned upload route: " + e.getMessage()));
        }
    }

    /**
     * 🎯 STEP 2 ENDPOINT: Processes video data using a lightweight link string instead of heavy files.
     * Accessible via POST: /api/analyze/squat (Payload: {"fileKey": "unique_uuid_video.mp4"})
     */
    @PostMapping("/squat")
    public ResponseEntity<Map<String, Object>> verifySquat(@RequestBody Map<String, String> payload) {
        String fileKey = payload.get("fileKey");
        if (fileKey == null || fileKey.isBlank()) {
            return ResponseEntity.status(400).body(Map.of("success", false, "error", "Missing required payload parameter: fileKey"));
        }

        File temporaryVideoFile = null;
        try {
            // 🎯 DOWNLOAD BUFFER: Stream the file from Supabase directly to your free container scratch space
            temporaryVideoFile = storageService.downloadFileFromStorage(fileKey);

            // 🎯 ANALYSIS CORES: Adapt your service signature interface loop to consume a temporary java.io.File wrapper
            // Note: Update your SquatAnalysisService to handle a File/InputStream parameter type.
            SquatAnalysisResult result = squatAnalysisService.analyzeSquatVideoFile(temporaryVideoFile);
            
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
        	LOGGER.error(e.getLocalizedMessage());
            return ResponseEntity.status(500).body(Map.of("success", false, "error", "Internal server core error: " + e.getMessage()));
        } finally {
            // 🎯 MEMORY GARBAGE COLLECTION TRAP: Clean up scratch space immediately to avoid running out of storage
            if (temporaryVideoFile != null && temporaryVideoFile.exists()) {
                temporaryVideoFile.delete();
            }
        }
    }
}
