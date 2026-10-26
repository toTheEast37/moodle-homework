package cn.example.service;

import cn.example.security.CredentialPasswordCipher;
import cn.example.repository.ExtractorCredentialKeyRepository;
import cn.example.repository.SiteCredentialRepository;
import java.util.Arrays;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SiteCredentialService {
    public static final String MOODLE_SITE_CODE = "scnu-moodle";
    public static final String MOODLE_SITE_NAME = "砺儒云课堂";
    public static final String SCHOLAT_SITE_CODE = "scholat";
    public static final String SCHOLAT_SITE_NAME = "学者网";

    private final SiteCredentialRepository credentials;
    private final ExtractorCredentialKeyRepository encryptionKeys;
    private final CredentialPasswordCipher cipher;

    public SiteCredentialService(SiteCredentialRepository credentials,
                                 ExtractorCredentialKeyRepository encryptionKeys,
                                 CredentialPasswordCipher cipher) {
        this.credentials = credentials;
        this.encryptionKeys = encryptionKeys;
        this.cipher = cipher;
    }

    public List<SiteCredentialRepository.CredentialSummary> list(long ownerUserId) {
        return credentials.findAllByOwner(ownerUserId);
    }

    public SiteCredentialRepository.CredentialSummary requireOwnedSummary(long ownerUserId, long id) {
        return credentials.findSummaryOwnedById(ownerUserId, id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "没有找到这个作业网站账号。"));
    }

    public SiteCredentialRepository.CredentialSummary save(long ownerUserId, SaveRequest request) {
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "账号信息不完整。");
        String siteCode = request.siteCode() == null ? MOODLE_SITE_CODE : request.siteCode();
        if (!MOODLE_SITE_CODE.equals(siteCode) && !SCHOLAT_SITE_CODE.equals(siteCode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持这个作业网站。");
        }
        String displayName = clean(request.displayName(), 100, "请填写账号备注名称。");
        String username = clean(request.loginUsername(), 128, "请填写作业网站账号。");
        if (request.password() != null && request.password().length() > 1024) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码长度超过限制。");
        }
        char[] password = request.password() == null ? new char[0] : request.password().toCharArray();
        try {
            if (request.id() != null) {
                SiteCredentialRepository.StoredCredential existing = credentials.findOwnedById(ownerUserId, request.id())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "没有找到这个作业网站账号。"));
                if (!siteCode.equals(existing.siteCode())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "已有账号不能切换作业网站，请新建绑定。");
                }
            }
            boolean hasPassword = password.length > 0;
            if (request.id() == null && !hasPassword) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新增账号时必须填写密码。");
            }
            CredentialPasswordCipher.EncryptedPassword encrypted = hasPassword
                    ? encryptPassword(ownerUserId, siteCode, password) : null;
            try {
                long id;
                if (request.id() == null) {
                    id = credentials.insert(ownerUserId, siteCode, displayName, username,
                            encrypted.nonce(), encrypted.ciphertext());
                } else {
                    if (credentials.findOwnedById(ownerUserId, request.id()).isEmpty()) {
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "没有找到这个作业网站账号。");
                    }
                    if (!credentials.updateOwned(ownerUserId, request.id(), siteCode, displayName, username,
                            encrypted == null ? null : encrypted.nonce(),
                            encrypted == null ? null : encrypted.ciphertext())) {
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "没有找到这个作业网站账号。");
                    }
                    id = request.id();
                }
                return credentials.findSummaryOwnedById(ownerUserId, id).orElseThrow(
                        () -> new IllegalStateException("保存后无法读取作业网站账号。"));
            } catch (DuplicateKeyException e) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "这个作业网站账号已绑定。", e);
            }
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    public LoginCredential requireForLogin(long ownerUserId, long id) {
        SiteCredentialRepository.StoredCredential stored = credentials.findOwnedById(ownerUserId, id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "没有找到这个作业网站账号。"));
        byte[] key = encryptionKeys.find(ownerUserId).orElseThrow(
                () -> new IllegalStateException("当前提取器账号没有密码加密密钥，请重新绑定作业网站账号并填写密码。"));
        try {
            return new LoginCredential(stored.loginUsername(),
                    cipher.decrypt(ownerUserId, stored.siteCode(), key, stored.nonce(), stored.ciphertext()));
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    public void delete(long ownerUserId, long id) {
        if (!credentials.deleteOwned(ownerUserId, id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "没有找到这个作业网站账号。");
        }
    }

    private static String clean(String value, int maxLength, String error) {
        String cleaned = value == null ? "" : value.strip();
        if (cleaned.isBlank() || cleaned.length() > maxLength) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error);
        }
        return cleaned;
    }

    private CredentialPasswordCipher.EncryptedPassword encryptPassword(long ownerUserId,
                                                                       String siteCode, char[] password) {
        byte[] key = encryptionKeys.getOrCreate(ownerUserId);
        try {
            return cipher.encrypt(ownerUserId, siteCode, key, password);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    public record SaveRequest(Long id, String siteCode, String displayName, String loginUsername, String password) {}

    public record LoginCredential(String username, char[] password) implements AutoCloseable {
        @Override public void close() { Arrays.fill(password, '\0'); }
    }
}
