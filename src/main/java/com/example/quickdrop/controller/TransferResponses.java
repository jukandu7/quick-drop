package com.example.quickdrop.controller;

import java.util.List;

/** HTTP payloads shared by the transfer controllers. */
public final class TransferResponses {
    private TransferResponses() { }

    public record RevokeResult(String message, List<String> revokedTokens) { }

    public record UploadResult(
            String message,
            String filename,
            String sharePath,
            String shareToken,
            String expirationLabel,
            String storageLabel,
            boolean requiresOverwrite
    ) {

        public static UploadResult error(String message) {
            return new UploadResult(
                    message, null, null, null, null, null, false
            );
        }

        public static UploadResult duplicate(String filename) {
            return new UploadResult(
                    "A file named " + filename + " already exists.",
                    filename,
                    null,
                    null,
                    null,
                    null,
                    true
            );
        }
    }

    public record DeleteRequest(List<String> tokens) {
    }

    public record ShareRequest(List<String> tokens) {
    }

    public record ReceiveLinkRequest(String expiration) {
    }

    public record ReceiveLinkResult(
            String message,
            String receivePath,
            String expirationLabel
    ) {
    }

    public record ReceiveLinkStatus(String message) {
    }

    public record ReceiveUploadResult(String message, String filename) {
    }

    public record UploadError(String message) {
    }

    public record BundleResult(
            String message,
            String sharePath,
            int fileCount
    ) {
    }

    public record DeleteResult(
            String message,
            String storageLabel,
            List<String> deletedTokens
    ) {
    }

    public record FileView(
            String filename,
            String shareToken,
            String expirationLabel
    ) {
    }

    public record FilesSnapshot(
            List<FileView> files,
            String storageLabel
    ) {
    }
}
