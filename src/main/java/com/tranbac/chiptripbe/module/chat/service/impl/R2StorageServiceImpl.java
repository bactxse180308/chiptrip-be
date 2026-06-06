package com.tranbac.chiptripbe.module.chat.service.impl;

import com.tranbac.chiptripbe.common.exception.AppException;
import com.tranbac.chiptripbe.module.chat.config.ChatProperties;
import com.tranbac.chiptripbe.module.chat.config.R2Properties;
import com.tranbac.chiptripbe.module.chat.service.R2StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
class R2StorageServiceImpl implements R2StorageService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp", "gif");
    private static final String KEY_PREFIX = "chiptrip/chat";

    private final S3Client s3Client;
    private final R2Properties r2Props;
    private final ChatProperties chatProps;

    @Override
    public UploadResult uploadChatImage(MultipartFile file, Long conversationId) {
        validate(file);

        String ext = extensionFromFile(file);
        String key = "%s/%d/%s.%s".formatted(KEY_PREFIX, conversationId, UUID.randomUUID(), ext);

        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(r2Props.getBucketName())
                            .key(key)
                            .contentType(file.getContentType())
                            .contentLength(file.getSize())
                            .build(),
                    RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
        } catch (IOException ex) {
            log.warn("R2 upload IO error for conversationId={}: {}", conversationId, ex.getClass().getSimpleName());
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "UPLOAD_FAILED",
                    "Không đọc được file ảnh", null);
        } catch (S3Exception ex) {
            log.warn("R2 upload S3 error for conversationId={}: status={} code={}",
                    conversationId, ex.statusCode(), ex.awsErrorDetails() != null ? ex.awsErrorDetails().errorCode() : "?");
            throw new AppException(HttpStatus.BAD_GATEWAY, "STORAGE_UNAVAILABLE",
                    "Không tải được ảnh lên lưu trữ", null);
        }

        String url = "%s/%s".formatted(stripTrailingSlash(r2Props.getPublicUrl()), key);
        return new UploadResult(url, key);
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "IMAGE_EMPTY", "File ảnh rỗng", null);
        }
        if (file.getSize() > chatProps.getImageMaxBytes()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "IMAGE_TOO_LARGE",
                    "Ảnh vượt quá %d MB".formatted(chatProps.getImageMaxBytes() / (1024 * 1024)), null);
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.toLowerCase().startsWith("image/")) {
            throw new AppException(HttpStatus.BAD_REQUEST, "IMAGE_INVALID_TYPE",
                    "Chỉ chấp nhận file ảnh", null);
        }
        String ext = extensionFromFile(file);
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "IMAGE_INVALID_TYPE",
                    "Định dạng ảnh không hỗ trợ (cho phép: %s)".formatted(String.join(", ", ALLOWED_EXTENSIONS)), null);
        }
    }

    private String extensionFromFile(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase();
    }

    private String stripTrailingSlash(String url) {
        if (url == null) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
