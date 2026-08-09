package com.mygym.app.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.mygym.app.config.SecurityConfig;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.File;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class StorageService {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(SecurityConfig.class);

    @Value("${AWS_S3_ENDPOINT}")
    private String endpoint;

    @Value("${AWS_S3_REGION}")
    private String region;

    @Value("${AWS_S3_ACCESS_KEY_ID}")
    private String accessKey;

    @Value("${AWS_S3_SECRET_ACCESS_KEY}")
    private String secretKey;
    
    @Value("${AWS_S3_BUCKET_NAME:gym-videos}")
    private String bucketName;

    public Map<String, String> generateUploadUrl(String originalFileName) {
        String uniqueFileName = UUID.randomUUID().toString() + "_" + originalFileName;
        
        // Direct System Environment Variable Credential Mappings
        System.setProperty("aws.accessKeyId", accessKey);
        System.setProperty("aws.secretAccessKey", secretKey);
        System.setProperty("aws.region", region);

        try (S3Presigner presigner = S3Presigner.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .chunkedEncodingEnabled(false) // Speeds up Flutter multi-part stream handshakes
                        .build())
                .build()) {

            PutObjectRequest objectRequest = PutObjectRequest.builder()
            		.bucket(bucketName)
                    .key(uniqueFileName)
                    .contentType("video/mp4")
                    .build();

            // 🎯 LINK UNLOCK WINDOW: Valid for 15 minutes max
            PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(15))
                    .putObjectRequest(objectRequest)
                    .build();

            PresignedPutObjectRequest presignedRequest = presigner.presignPutObject(presignRequest);
            
            Map<String, String> responseMap = new HashMap<>();
            responseMap.putAll(Map.of(
                "uploadUrl", presignedRequest.url().toString(),
                "fileKey", uniqueFileName
            ));
            return responseMap;
        }
    }
    
    /**
     * Streams video chunks from the bucket straight onto the platform's free temporary file scratchpad.
     */
    public File downloadFileFromStorage(String fileKey) throws Exception {
        System.setProperty("aws.accessKeyId", accessKey);
        System.setProperty("aws.secretAccessKey", secretKey);

        try (software.amazon.awssdk.services.s3.S3Client s3Client = software.amazon.awssdk.services.s3.S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .forcePathStyle(true)
                .build()) {

            software.amazon.awssdk.services.s3.model.GetObjectRequest getObjectRequest = software.amazon.awssdk.services.s3.model.GetObjectRequest.builder()
            		.bucket(bucketName)
                    .key(fileKey)
                    .build();

            // Create a temporary tracking file descriptor path within the runtime container
            File tempFile = File.createTempFile("mga_upload_", ".mp4");
            
            try (software.amazon.awssdk.core.ResponseInputStream<?> s3Stream = s3Client.getObject(getObjectRequest)) {
                
                java.nio.file.Files.copy(s3Stream, tempFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            
            LOGGER.info("🎯 Successfully downloaded video file onto container scratchpad. Size: {} bytes", tempFile.length());
            return tempFile;
        }
    }
    
    /**
     * 🎯 IMMEDIATE CLOUD CLEANUP
     * Erases the targeted video file from Backblaze B2 instantly.
     */
    public void deleteFileFromStorage(String fileKey) {
        if (fileKey == null || fileKey.isBlank()) return;

        System.setProperty("aws.accessKeyId", accessKey);
        System.setProperty("aws.secretAccessKey", secretKey);
        System.setProperty("aws.region", region);

        try (software.amazon.awssdk.services.s3.S3Client s3Client = software.amazon.awssdk.services.s3.S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(software.amazon.awssdk.regions.Region.of(region))
                .forcePathStyle(true)
                .build()) {

            software.amazon.awssdk.services.s3.model.DeleteObjectRequest deleteRequest = 
                software.amazon.awssdk.services.s3.model.DeleteObjectRequest.builder()
                    .bucket("gym-videos")
                    .key(fileKey)
                    .build();

            s3Client.deleteObject(deleteRequest);
            LOGGER.info("🗑️ Successfully deleted temporary file [{}] from Backblaze B2 storage.", fileKey);

        } catch (Exception e) {
            // Log as error but do not throw, so it doesn't interrupt the user's workout feedback response
            LOGGER.error("⚠️ Failed to delete file [{}] from Backblaze storage: {}", fileKey, e.getMessage());
        }
    }


}
