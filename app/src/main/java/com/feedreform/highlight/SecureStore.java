package com.feedreform.highlight;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 本机凭据：Android Keystore AES/GCM 加密后存 SharedPreferences。
 *
 * 只保存两样东西：B站 Cookie、DeepSeek Key。Keystore 不可用（模拟器缺硬件密钥、密钥被清）时
 * 降级为「不可用」，读取返回 null、写入返回 false，不崩溃也不回落到明文。
 * 日志只打长度或前 6 位，永远不打完整内容。
 */
final class SecureStore {

    private static final String TAG = "HLBASE";
    private static final String PREF = "hl_secure";
    private static final String ALIAS = "highlight_store_v1";

    private static final String COOKIE_ENC = "cookie_enc";
    private static final String COOKIE_IV = "cookie_iv";
    private static final String KEY_ENC = "key_enc";
    private static final String KEY_IV = "key_iv";

    /** Keystore 出错后置 false；后续写入直接失败，界面据此提示「本机保存不可用」。 */
    private static volatile boolean available = true;

    private SecureStore() {
    }

    static boolean isAvailable() {
        return available;
    }

    static void setCookie(Context c, String value) {
        set(c, COOKIE_ENC, COOKIE_IV, value);
    }

    static String getCookie(Context c) {
        return get(c, COOKIE_ENC, COOKIE_IV);
    }

    static void setKey(Context c, String value) {
        set(c, KEY_ENC, KEY_IV, value);
    }

    static String getKey(Context c) {
        return get(c, KEY_ENC, KEY_IV);
    }

    static void clearCookie(Context c) {
        prefs(c).edit().remove(COOKIE_ENC).remove(COOKIE_IV).apply();
    }

    static void clearKey(Context c) {
        prefs(c).edit().remove(KEY_ENC).remove(KEY_IV).apply();
    }

    static void clearAll(Context c) {
        prefs(c).edit().clear().apply();
    }

    /** 界面用：只露出前 5 位与后 4 位（sk-••••••1234），不含长度等诊断信息。 */
    static String mask(String value) {
        if (value == null || value.isEmpty()) return "";
        if (value.length() <= 11) return "••••••";
        return value.substring(0, 5) + "••••••" + value.substring(value.length() - 4);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static void set(Context c, String encKey, String ivKey, String value) {
        String plain = value == null ? "" : value.trim();
        if (plain.isEmpty()) {
            prefs(c).edit().remove(encKey).remove(ivKey).apply();
            return;
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] enc = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            prefs(c).edit()
                    .putString(encKey, Base64.encodeToString(enc, Base64.NO_WRAP))
                    .putString(ivKey, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .apply();
            available = true;
            Log.i(TAG, "secure-set " + encKey + " len=" + plain.length());
        } catch (Throwable t) {
            available = false;
            Log.w(TAG, "secure-set failed: " + t.getClass().getSimpleName());
        }
    }

    private static String get(Context c, String encKey, String ivKey) {
        String enc = prefs(c).getString(encKey, null);
        String iv = prefs(c).getString(ivKey, null);
        if (enc == null || iv == null) return null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            byte[] out = cipher.doFinal(Base64.decode(enc, Base64.NO_WRAP));
            String plain = new String(out, StandardCharsets.UTF_8).trim();
            if (plain.isEmpty()) return null;
            return plain;
        } catch (Throwable t) {
            available = false;
            Log.w(TAG, "secure-get failed: " + t.getClass().getSimpleName());
            return null;
        }
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) {
            return (SecretKey) ks.getKey(ALIAS, null);
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }
}
