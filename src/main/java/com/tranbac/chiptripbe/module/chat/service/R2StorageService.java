package com.tranbac.chiptripbe.module.chat.service;

import org.springframework.web.multipart.MultipartFile;

public interface R2StorageService {

    /**
     * Validate + upload ảnh chat lên R2.
     * Trả về (imageUrl, imageKey). imageUrl = publicUrl + "/" + key.
     */
    UploadResult uploadChatImage(MultipartFile file, Long conversationId);

    record UploadResult(String imageUrl, String imageKey) {}
}
