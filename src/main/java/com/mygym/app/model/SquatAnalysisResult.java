package com.mygym.app.model;

import java.util.List;

public record SquatAnalysisResult(
    String detectedExercise,
    int totalReps,
    double deepestKneeAngle,
    double maxForwardLeanAngle,
    boolean buttWinkDetected,
    String feedback,
    List<Double> repAngles,
    String processedVideoUrl
) {}
