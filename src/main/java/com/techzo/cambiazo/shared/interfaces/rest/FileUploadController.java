package com.techzo.cambiazo.shared.interfaces.rest;

import com.techzo.cambiazo.iam.infrastructure.persistence.jpa.repositories.UserRepository;
import com.techzo.cambiazo.shared.infrastructure.storage.AzureBlobStorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("api/v2/files")
@Tag(name = "Files", description = "File Upload Endpoints")
public class FileUploadController {

    private final AzureBlobStorageService storageService;
    private final UserRepository userRepository;

    public FileUploadController(AzureBlobStorageService storageService, UserRepository userRepository) {
        this.storageService = storageService;
        this.userRepository = userRepository;
    }

    @Operation(summary = "Upload a product image")
    @PostMapping(value = "/products/{productId}", consumes = "multipart/form-data")
    public ResponseEntity<?> uploadProductImage(
            @PathVariable String productId,
            @RequestParam("file") MultipartFile file
    ) {
        try {
            String url = storageService.upload(file, "products/" + productId);
            return ResponseEntity.ok(Map.of("url", url));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @Operation(summary = "Upload a profile picture and update the user's profilePicture in DB")
    @PostMapping(value = "/profiles/{userId}", consumes = "multipart/form-data")
    public ResponseEntity<?> uploadProfileImage(
            @PathVariable Long userId,
            @RequestParam("file") MultipartFile file
    ) {
        try {
            String url = storageService.uploadReplacingFolder(file, "profiles/" + userId);

            userRepository.findById(userId).ifPresent(user -> {
                user.setProfilePicture(url);
                userRepository.save(user);
            });

            return ResponseEntity.ok(Map.of("url", url));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
