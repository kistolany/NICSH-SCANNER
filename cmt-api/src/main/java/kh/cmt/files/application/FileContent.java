package kh.cmt.files.application;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import javax.imageio.ImageIO;
import kh.cmt.files.api.FilePurpose.FileTypes;

/**
 * What a file really is, from its bytes (never from what the client said), and the image work: images larger than
 * {@link #MAX_SIDE} pixels are scaled down, every image gets a JPEG thumbnail of at most {@link #THUMB_SIDE} pixels.
 */
final class FileContent {

    static final int MAX_SIDE = 2000;
    static final int THUMB_SIDE = 320;

    private FileContent() {
    }

    /** image/jpeg, image/png or application/pdf by the first bytes; empty for anything else. */
    static Optional<String> sniff(byte[] bytes) {
        if (startsWith(bytes, 0xFF, 0xD8, 0xFF)) {
            return Optional.of(FileTypes.JPEG);
        }
        if (startsWith(bytes, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return Optional.of(FileTypes.PNG);
        }
        if (bytes.length >= 5 && new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) {
            return Optional.of(FileTypes.PDF);
        }
        return Optional.empty();
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The decoded image; empty if the bytes cannot be read as one (broken, or an unsupported variant such as CMYK). */
    static Optional<BufferedImage> decode(byte[] bytes) {
        try {
            return Optional.ofNullable(ImageIO.read(new ByteArrayInputStream(bytes)));
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    /** The image scaled so that its longer side is at most {@code side} (unchanged if already smaller). */
    static BufferedImage fit(BufferedImage image, int side, boolean opaque) {
        int w = image.getWidth();
        int h = image.getHeight();
        double factor = Math.min(1.0, (double) side / Math.max(w, h));
        int nw = Math.max(1, (int) Math.round(w * factor));
        int nh = Math.max(1, (int) Math.round(h * factor));
        if (factor >= 1.0 && !opaque) {
            return image;
        }
        BufferedImage out = new BufferedImage(nw, nh, opaque ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            if (opaque) {
                g.setColor(Color.WHITE);   // transparent parts of a PNG become white in a JPEG
                g.fillRect(0, 0, nw, nh);
            }
            g.drawImage(image, 0, 0, nw, nh, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** Encodes as {@code jpg} or {@code png}. */
    static byte[] encode(BufferedImage image, String format) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(image, format, out)) {
                throw new IllegalStateException("No image writer for " + format);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        return Arrays.equals(Arrays.copyOf(bytes, prefix.length), toBytes(prefix));
    }

    private static byte[] toBytes(int[] values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }
}
