package kh.cmt.support;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.NotFoundException;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Reads the QR on a printed sheet like a phone would. ZXing's detector misses about 1 sheet in 100 on the perfectly
 * sharp 300 dpi render (the QR itself is fine: read as a pure barcode, every one decodes), and a phone simply reads the
 * next camera frame — so this tries the full image, then smaller ones, as a phone sees it from further away.
 */
public final class QrImages {

    private static final int[] WIDTHS = {0, 1240, 827, 620};

    private QrImages() {
    }

    public static String read(byte[] png) throws IOException, NotFoundException {
        return read(ImageIO.read(new ByteArrayInputStream(png)));
    }

    public static String read(BufferedImage image) throws NotFoundException {
        for (int width : WIDTHS) {
            BufferedImage frame = width == 0 || width >= image.getWidth() ? image : scaled(image, width);
            int[] pixels = frame.getRGB(0, 0, frame.getWidth(), frame.getHeight(), null, 0, frame.getWidth());
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(frame.getWidth(), frame.getHeight(), pixels)));
            try {
                return new QRCodeReader().decode(bitmap, Map.of(DecodeHintType.TRY_HARDER, true)).getText();
            } catch (ReaderException missed) {
                // the next "frame"
            }
        }
        throw NotFoundException.getNotFoundInstance();
    }

    private static BufferedImage scaled(BufferedImage image, int width) {
        Image s = image.getScaledInstance(width, width * image.getHeight() / image.getWidth(), Image.SCALE_SMOOTH);
        BufferedImage out = new BufferedImage(s.getWidth(null), s.getHeight(null), BufferedImage.TYPE_INT_RGB);
        out.getGraphics().drawImage(s, 0, 0, null);
        return out;
    }
}
