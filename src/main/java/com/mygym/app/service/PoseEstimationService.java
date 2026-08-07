package com.mygym.app.service;

import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.mygym.app.model.Keypoints;
import org.springframework.stereotype.Service;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.Collections;

@Service
public class PoseEstimationService {

    private final OrtEnvironment env;
    private final OrtSession session;
    
    // ThreadLocal buffer reuse optimizes memory allocation overhead during parallel frames extraction
    private final ThreadLocal<ByteBuffer> threadBuffer = ThreadLocal.withInitial(() -> 
        ByteBuffer.allocateDirect(1 * 192 * 192 * 4)
    );

    public PoseEstimationService(OrtEnvironment env, OrtSession session) {
        this.env = env;
        this.session = session;
    }

    public Keypoints predictKeypoints(BufferedImage image) throws Exception {
        BufferedImage resized = new BufferedImage(192, 192, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = resized.createGraphics();
        g.drawImage(image, 0, 0, 192, 192, null);
        g.dispose();

        ByteBuffer inputBuffer = threadBuffer.get();
        inputBuffer.clear();

        for (int y = 0; y < 192; y++) {
            for (int x = 0; x < 192; x++) {
                int rgb = resized.getRGB(x, y);
                inputBuffer.put((byte) ((rgb >> 16) & 0xFF)); 
                inputBuffer.put((byte) ((rgb >> 8) & 0xFF));  
                inputBuffer.put((byte) (rgb & 0xFF));         
                inputBuffer.put((byte) 0);                    
            }
        }
        inputBuffer.rewind();

        // FIX: Wrapped inside a try-with-resources block to force clean the native C++ allocations
        try (OnnxTensor inputTensor = OnnxTensor.createTensor(env, inputBuffer, new long[]{1, 192, 192, 4}, OnnxJavaType.UINT8);
             OrtSession.Result results = session.run(Collections.singletonMap("pixel_values", inputTensor))) {
            
            float[][][][] output4D = (float[][][][]) results.get(0).getValue();
            float[][] keypoints = output4D[0][0]; 

            double shoulderY = keypoints[5][0] * 192.0;
            double shoulderX = keypoints[5][1] * 192.0;
            double hipY      = keypoints[11][0] * 192.0;
            double hipX      = keypoints[11][1] * 192.0;
            double kneeY     = keypoints[13][0] * 192.0;
            double kneeX     = keypoints[13][1] * 192.0;
            double ankleY    = keypoints[15][0] * 192.0;
            double ankleX    = keypoints[15][1] * 192.0;

            double leftSideConfidence = keypoints[5][2] + keypoints[11][2];
            double rightSideConfidence = keypoints[6][2] + keypoints[12][2];

            if (rightSideConfidence > leftSideConfidence) {
                shoulderY = keypoints[6][0] * 192.0;
                shoulderX = keypoints[6][1] * 192.0;
                hipY      = keypoints[12][0] * 192.0;
                hipX      = keypoints[12][1] * 192.0;
                kneeY     = keypoints[14][0] * 192.0;
                kneeX     = keypoints[14][1] * 192.0;
                ankleY    = keypoints[16][0] * 192.0;
                ankleX    = keypoints[16][1] * 192.0;
            }

            return new Keypoints(hipX, hipY, kneeX, kneeY, ankleX, ankleY, shoulderX, shoulderY);
        }
    }
}
