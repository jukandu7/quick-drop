package com.example.quickdrop.controller;

import com.example.quickdrop.config.TransferLimits;
import com.example.quickdrop.presentation.DisplayFormats;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import static com.example.quickdrop.controller.TransferResponses.*;

@ControllerAdvice(assignableTypes = {HomeController.class, UploadController.class, DownloadController.class,
        ReceivingController.class, FileManagementController.class, QrController.class})
public class TransferExceptionHandler {
    private final TransferLimits limits;
    public TransferExceptionHandler(TransferLimits limits) { this.limits = limits; }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<UploadError> handleUploadTooLarge() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new UploadError(
                        "Upload rejected: maximum file size is " + DisplayFormats.size(limits.fileSize().toBytes())
                                + "; maximum request size is " + DisplayFormats.size(limits.requestSize().toBytes()) + "."
                ));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<UploadError> handleUploadConflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new UploadError(
                        "That filename changed during the upload. "
                                + "Please try it again."
                ));
    }

}
