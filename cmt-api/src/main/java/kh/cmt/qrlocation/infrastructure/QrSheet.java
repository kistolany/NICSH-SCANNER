package kh.cmt.qrlocation.infrastructure;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.stereotype.Component;

/**
 * The sheet posted at a gate, as in the design's "Create QR" preview: the school's logo and name, the gate's name, the QR
 * code, "ស្កេនដើម្បីចុះវត្តមាន" and "Scan to check in", and a small line with the version. Drawn with Java2D (it shapes
 * Khmer correctly; PDF text would not) at A4 300 dpi: a PNG, or that image on an A4 PDF page.
 */
@Component
public class QrSheet {

    /** A4 at 300 dpi. */
    static final int WIDTH = 2480;
    static final int HEIGHT = 3508;
    private static final Color INK = new Color(0x1F1A17);
    private static final Color SUB = new Color(0x7A6A5E);
    private static final Color ACCENT = new Color(0xC2410C);
    private static final Color LINE = new Color(0xEFE3D9);

    private final Font khmer = font("Siemreap-400.ttf");
    private final Font bold = font("PlusJakartaSans-800.ttf");
    private final Font semi = font("PlusJakartaSans-700.ttf");
    private final Font regular = font("PlusJakartaSans-400.ttf");

    /** {@code logo}: the school's logo image (PNG / JPEG bytes), or null. */
    public record Content(String payload, String schoolName, byte[] logo, String gateName, String areaName, String footer) {
    }

    public byte[] png(Content c) {
        return encode(draw(c));
    }

    public byte[] pdf(Content c) {
        BufferedImage image = draw(c);
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            doc.getDocumentInformation().setTitle("QR " + c.gateName());
            doc.getDocumentInformation().setCreator("CMT School");
            PDImageXObject picture = LosslessFactory.createFromImage(doc, image);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.drawImage(picture, 0, 0, PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight());
            }
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    BufferedImage draw(Content c) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, WIDTH, HEIGHT);
            // the card's frame
            g.setColor(LINE);
            g.setStroke(new BasicStroke(8));
            g.draw(new RoundRectangle2D.Double(160, 160, WIDTH - 320, HEIGHT - 320, 120, 120));

            // school: logo + name
            int y = 480;
            FontMetrics school = g.getFontMetrics(bold.deriveFont(110f));
            int logoSize = 200;
            BufferedImage logo = logo(c.logo());
            int rowWidth = (logo != null ? logoSize + 60 : 0) + school.stringWidth(c.schoolName());
            int x = (WIDTH - rowWidth) / 2;
            if (logo != null) {
                g.setClip(new Ellipse2D.Double(x, y - logoSize / 2.0 - 30, logoSize, logoSize));
                g.drawImage(logo, x, y - logoSize / 2 - 30, logoSize, logoSize, null);
                g.setClip(null);
                x += logoSize + 60;
            }
            g.setColor(INK);
            g.setFont(school.getFont());
            g.drawString(c.schoolName(), x, y + school.getAscent() / 2 - 30);

            // gate
            y = 840;
            centered(g, c.gateName(), bold.deriveFont(190f), INK, y);
            if (c.areaName() != null) {
                centered(g, c.areaName(), regular.deriveFont(80f), SUB, y + 130);
            }

            // the QR code
            int qrSize = 1480;
            int qrTop = 1080;
            drawQr(g, c.payload(), (WIDTH - qrSize) / 2, qrTop, qrSize);

            // "Scan to check in" in Khmer, then English
            y = qrTop + qrSize + 270;   // 2830
            centered(g, "ស្កេនដើម្បីចុះវត្តមាន", khmer.deriveFont(150f), INK, y);
            centered(g, "Scan to check in", semi.deriveFont(130f), INK, y + 200);
            // accent bar and the version line, inside the frame (which ends at HEIGHT - 160)
            g.setColor(ACCENT);
            g.fillRoundRect(WIDTH / 2 - 160, y + 270, 320, 18, 18, 18);
            centered(g, c.footer(), regular.deriveFont(62f), SUB, HEIGHT - 230);
        } finally {
            g.dispose();
        }
        return image;
    }

    /**
     * The QR in a {@code size} box that includes the standard quiet zone of 4 modules, so the text above and below never
     * comes closer than that (with no margin, about 1 sheet in 80 could not be read).
     */
    private static void drawQr(Graphics2D g, String payload, int left, int top, int size) {
        BitMatrix m;
        try {
            m = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0,
                    Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 4));
        } catch (WriterException e) {
            throw new IllegalStateException("QR content does not fit", e);
        }
        int modules = m.getWidth();
        int cell = size / modules;
        int offset = (size - cell * modules) / 2;
        g.setColor(INK);
        for (int row = 0; row < modules; row++) {
            for (int col = 0; col < modules; col++) {
                if (m.get(col, row)) {
                    g.fillRect(left + offset + col * cell, top + offset + row * cell, cell, cell);
                }
            }
        }
    }

    private static void centered(Graphics2D g, String text, Font font, Color color, int baseline) {
        g.setFont(font);
        g.setColor(color);
        FontMetrics fm = g.getFontMetrics();
        String shown = text;
        while (fm.stringWidth(shown) > WIDTH - 480 && shown.length() > 4) {
            shown = shown.substring(0, shown.length() - 2) + "…";
        }
        g.drawString(shown, (WIDTH - fm.stringWidth(shown)) / 2, baseline);
    }

    private static BufferedImage logo(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        try {
            return ImageIO.read(new java.io.ByteArrayInputStream(bytes));
        } catch (IOException unreadable) {
            return null;
        }
    }

    private static byte[] encode(BufferedImage image) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Font font(String file) {
        try (InputStream in = QrSheet.class.getResourceAsStream("/fonts/" + file)) {
            if (in == null) {
                throw new IllegalStateException("Missing font /fonts/" + file);
            }
            return Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (IOException | FontFormatException e) {
            throw new IllegalStateException("Cannot load font " + file, e);
        }
    }
}
