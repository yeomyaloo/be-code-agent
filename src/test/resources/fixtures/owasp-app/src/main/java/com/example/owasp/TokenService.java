package com.example.owasp;

import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Random;

@Service
public class TokenService {

    // 비밀번호 재설정 토큰을 예측 가능한 난수로 만든다
    public String resetToken(String email) {
        return email + ":" + new Random().nextInt(1_000_000);
    }

    // 비밀번호를 MD5 로 해시한다
    public String hashPassword(String password) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(password.getBytes()));
    }

    public Cipher legacyCipher() throws Exception {
        return Cipher.getInstance("DES/ECB/PKCS5Padding");
    }
}
