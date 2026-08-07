package com.mygym.app.config;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.io.InputStream;

@Configuration
public class OnnxConfig {

    @Bean
    public OrtEnvironment ortEnvironment() {
        try {
            // Explicitly force the native C++ ONNX library layer to load into the JVM
            return OrtEnvironment.getEnvironment();
        } catch (Exception e) {
            throw new RuntimeException("CRITICAL: Failed to load native C++ ONNX environment drivers. Check OS compatibility.", e);
        }
    }

    @Bean
    public OrtSession ortSession(OrtEnvironment env) {
        String modelPath = "/models/movenet_lightning.onnx";
        try (InputStream modelStream = getClass().getResourceAsStream(modelPath)) {
            if (modelStream == null) {
                throw new IllegalStateException("ONNX model file was not found at: src/main/resources" + modelPath);
            }
            byte[] modelBytes = modelStream.readAllBytes();
            return env.createSession(modelBytes);
        } catch (Exception e) {
            throw new RuntimeException("CRITICAL: Failed to initialize OrtSession with the model graph.", e);
        }
    }
}
