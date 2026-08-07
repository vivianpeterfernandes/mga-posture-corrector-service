package com.mygym.app.model;

public record Keypoints(
    double hipX, double hipY, 
    double kneeX, double kneeY, 
    double ankleX, double ankleY,
    double shoulderX, double shoulderY // Added to calculate true spinal spine lean
) {}
