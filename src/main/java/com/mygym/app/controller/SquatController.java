package com.mygym.app.controller;

import com.mygym.app.model.SquatAnalysisResult;
import com.mygym.app.service.SquatAnalysisService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.Map;

@RestController
@RequestMapping({"/api/analyze","/api/analyze/"})
public class SquatController {

    private final SquatAnalysisService squatAnalysisService;

    public SquatController(SquatAnalysisService squatAnalysisService) {
        this.squatAnalysisService = squatAnalysisService;
    }

    @PostMapping("/squat")
    public ResponseEntity<Map<String, Object>> verifySquat(@RequestParam("file") MultipartFile file) {
        try {
            SquatAnalysisResult result = squatAnalysisService.analyzeSquatVideo(file);
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "detectedExercise", result.detectedExercise(),
                "repsCounted", result.totalReps(),
                "deepestKneeAngle", Math.round(result.deepestKneeAngle()),
                "maxForwardLeanAngle", Math.round(result.maxForwardLeanAngle()),
                "repAnglesBreakdown", result.repAngles(),
                //To be added if better processor cloud platform available
                //"processedVideoDownloadUrl", "http://localhost:8080" + result.processedVideoUrl(),
                "feedback", result.feedback()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(400).body(Map.of(
                "success", false, 
                "error", e.getLocalizedMessage()
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                "success", false, 
                "error", "Internal server core error: " + e.getMessage()
            ));
        }
    }
}
