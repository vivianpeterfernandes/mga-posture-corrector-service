package com.mygym.app.service;

import java.io.File;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Service
public class StorageService {

    private static final Logger LOGGER = LoggerFactory.getLogger(StorageService.class);

    private final S3Presigner presigner;
    private final S3Client s3Client;

    @Value("${AWS_S3_BUCKET_NAME:gym-videos}")
    private String bucketName;

    public StorageService(S3Presigner presigner, S3Client s3Client) {
        this.presigner = presigner;
        this.s3Client = s3Client;
    }

    public Map<String, String> generateUploadUrl(String originalFileName) {
        String uniqueFileName = UUID.randomUUID().toString() + "_" + originalFileName;

        PutObjectRequest objectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(uniqueFileName)
                .contentType("video/mp4")
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(15))
                .putObjectRequest(objectRequest)
                .build();

        PresignedPutObjectRequest presignedRequest = presigner.presignPutObject(presignRequest);

        Map<String, String> responseMap = new HashMap<>();
        responseMap.put("uploadUrl", presignedRequest.url().toString());
        responseMap.put("fileKey", uniqueFileName);
        return responseMap;
    }

    public File downloadFileFromStorage(String fileKey) throws Exception {
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(fileKey)
                .build();

        File tempFile = File.createTempFile("mga_upload_", ".mp4");

        try (var s3Stream = s3Client.getObject(getObjectRequest)) {
            java.nio.file.Files.copy(s3Stream, tempFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        LOGGER.info("🎯 Successfully downloaded video file onto container scratchpad. Size: {} bytes", tempFile.length());
        return tempFile;
    }

    public void deleteFileFromStorage(String fileKey) {
        if (fileKey == null || fileKey.isBlank()) return;

        try {
            DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                    .bucket(bucketName) // ✅ FIX: Uses injected bucketName instead of hardcoded string
                    .key(fileKey)
                    .build();

            s3Client.deleteObject(deleteRequest);
            LOGGER.info("🗑️ Successfully deleted temporary file [{}] from Backblaze B2 storage.", fileKey);
        } catch (Exception e) {
            LOGGER.error("⚠️ Failed to delete file [{}] from Backblaze storage: {}", fileKey, e.getMessage());
        }
    }
}