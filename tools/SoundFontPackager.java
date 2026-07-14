import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.Arrays;

public final class SoundFontPackager {
    private static final byte[] MAGIC = new byte[] {
            (byte) 0x9d, 0x72, (byte) 0xb4, 0x1e,
            0x43, (byte) 0xe8, 0x0d, (byte) 0xa6
    };
    private static final int NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private SoundFontPackager() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 && args.length != 3) {
            System.err.println("Usage: java SoundFontPackager <input> <output> [hex-encoded-32-byte-key]");
            System.err.println("Or set XENSYNTH_SF_KEY_HEX and omit the key argument.");
            System.exit(2);
        }

        String encodedKey = args.length == 3 ? args[2] : System.getenv("XENSYNTH_SF_KEY_HEX");
        if (encodedKey == null || encodedKey.isBlank()) {
            throw new IllegalArgumentException("Missing AES-256 key");
        }
        byte[] key = hexToBytes(encodedKey.trim());
        if (key.length != 32) {
            Arrays.fill(key, (byte) 0);
            throw new IllegalArgumentException("AES-256 key must be 32 bytes");
        }

        byte[] plaintext = null;
        byte[] encrypted = null;
        byte[] packaged = null;
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            new SecureRandom().nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, nonce));
            Path inputPath = Paths.get(args[0]);
            Path outputPath = Paths.get(args[1]);
            plaintext = Files.readAllBytes(inputPath);
            encrypted = cipher.doFinal(plaintext);
            packaged = new byte[MAGIC.length + nonce.length + encrypted.length];
            System.arraycopy(MAGIC, 0, packaged, 0, MAGIC.length);
            System.arraycopy(nonce, 0, packaged, MAGIC.length, nonce.length);
            System.arraycopy(encrypted, 0, packaged, MAGIC.length + nonce.length, encrypted.length);
            Files.createDirectories(outputPath.toAbsolutePath().getParent());
            Files.write(outputPath, packaged);
        } finally {
            Arrays.fill(key, (byte) 0);
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
            if (encrypted != null) {
                Arrays.fill(encrypted, (byte) 0);
            }
            if (packaged != null) {
                Arrays.fill(packaged, (byte) 0);
            }
        }
    }

    private static byte[] hexToBytes(String hex) {
        if ((hex.length() & 1) != 0) {
            throw new IllegalArgumentException("Hex key must contain an even number of characters");
        }
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int offset = i * 2;
            bytes[i] = (byte) Integer.parseInt(hex.substring(offset, offset + 2), 16);
        }
        return bytes;
    }
}
