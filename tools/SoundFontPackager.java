import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;

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
        if (args.length != 3) {
            System.err.println("Usage: java SoundFontPackager <input> <output> <hex-encoded-32-byte-key>");
            System.exit(2);
        }

        byte[] key = hexToBytes(args[2]);
        if (key.length != 32) {
            throw new IllegalArgumentException("AES-256 key must be 32 bytes");
        }

        byte[] nonce = new byte[NONCE_BYTES];
        new SecureRandom().nextBytes(nonce);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
        Path inputPath = Paths.get(args[0]);
        Path outputPath = Paths.get(args[1]);
        byte[] encrypted = cipher.doFinal(Files.readAllBytes(inputPath));

        ByteArrayOutputStream output = new ByteArrayOutputStream(MAGIC.length + nonce.length + encrypted.length);
        output.write(MAGIC);
        output.write(nonce);
        output.write(encrypted);
        Files.createDirectories(outputPath.toAbsolutePath().getParent());
        Files.write(outputPath, output.toByteArray());
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
