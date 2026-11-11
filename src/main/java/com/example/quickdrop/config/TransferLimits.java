package com.example.quickdrop.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

/** Shared, validated limits for the servlet, storage service, and rendered UI. */
@Component
public record TransferLimits(DataSize fileSize, DataSize requestSize, DataSize storageQuota,
                             DataSize tempBudget, DataSize minimumFreeSpace, int maxConcurrent) {
    public TransferLimits(
            @Value("${spring.servlet.multipart.max-file-size:3GB}") DataSize fileSize,
            @Value("${spring.servlet.multipart.max-request-size:3073MB}") DataSize requestSize,
            @Value("${quickdrop.storage-quota:5GB}") DataSize storageQuota,
            @Value("${quickdrop.upload.temp-budget:7GB}") DataSize tempBudget,
            @Value("${quickdrop.upload.minimum-free-space:512MB}") DataSize minimumFreeSpace,
            @Value("${quickdrop.upload.max-concurrent:2}") int maxConcurrent) {
        if (fileSize.toBytes() <= 0 || requestSize.toBytes() < fileSize.toBytes()
                || requestSize.toBytes() > Long.MAX_VALUE / 2 || storageQuota.toBytes() < fileSize.toBytes()
                || tempBudget.toBytes() < 2 * requestSize.toBytes()
                || minimumFreeSpace.toBytes() < 0 || maxConcurrent < 1)
            throw new IllegalArgumentException("Invalid transfer limits: request and storage must cover a file; temporary budget must cover twice a request; sizes and concurrency must be positive.");
        this.fileSize = fileSize;
        this.requestSize = requestSize;
        this.storageQuota = storageQuota;
        this.tempBudget = tempBudget;
        this.minimumFreeSpace = minimumFreeSpace;
        this.maxConcurrent = maxConcurrent;
    }
}
