package com.techzo.cambiazo.shared.infrastructure.storage;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobHttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

@Service
public class AzureBlobStorageService {

    private final BlobContainerClient containerClient;

    public AzureBlobStorageService(BlobContainerClient containerClient) {
        this.containerClient = containerClient;
    }

    public String upload(MultipartFile file, String folder) throws IOException {
        String original = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
        String safeName = original.replaceAll("[^a-zA-Z0-9._-]", "_");
        String blobName = folder + "/" + UUID.randomUUID() + "-" + safeName;

        BlobClient blobClient = containerClient.getBlobClient(blobName);
        blobClient.upload(file.getInputStream(), file.getSize(), true);
        blobClient.setHttpHeaders(new BlobHttpHeaders().setContentType(file.getContentType()));

        return buildPublicUrl(blobName);
    }

    public String uploadReplacingFolder(MultipartFile file, String folder) throws IOException {
        deleteFolder(folder);
        return upload(file, folder);
    }

    public void deleteFolder(String folder) {
        String prefix = folder.endsWith("/") ? folder : folder + "/";
        containerClient.listBlobs().forEach(item -> {
            if (item.getName().startsWith(prefix)) {
                containerClient.getBlobClient(item.getName()).deleteIfExists();
            }
        });
    }

    public void deleteByUrl(String url) {
        if (url == null || url.isBlank()) return;
        String containerUrl = containerClient.getBlobContainerUrl();
        if (!url.startsWith(containerUrl + "/")) return;
        String blobName = url.substring(containerUrl.length() + 1);
        containerClient.getBlobClient(blobName).deleteIfExists();
    }

    public String uploadBytes(byte[] data, String blobName, String contentType) {
        BlobClient blobClient = containerClient.getBlobClient(blobName);
        blobClient.upload(new java.io.ByteArrayInputStream(data), data.length, true);
        blobClient.setHttpHeaders(new BlobHttpHeaders().setContentType(contentType));
        return buildPublicUrl(blobName);
    }

    private String buildPublicUrl(String blobName) {
        return containerClient.getBlobContainerUrl() + "/" + blobName;
    }
}
