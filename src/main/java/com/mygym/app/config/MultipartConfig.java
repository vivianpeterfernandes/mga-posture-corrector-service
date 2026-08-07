package com.mygym.app.config;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.boot.web.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

@Configuration
public class MultipartConfig {

    @Bean
    public MultipartConfigElement multipartConfigElement() {
        MultipartConfigFactory factory = new MultipartConfigFactory();
        
        // 🎯 FORCE UNCAP ALL MULTIPART LIMITS (Allows up to 200MB streams)
        factory.setMaxFileSize(DataSize.ofMegabytes(200));
        factory.setMaxRequestSize(DataSize.ofMegabytes(210));
        
        // Keep large files entirely inside the fast container RAM pool
        factory.setFileSizeThreshold(DataSize.ofMegabytes(150));
        
        return factory.createMultipartConfig();
    }
}
