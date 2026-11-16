package kh.cmt.qrlocation.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import javax.imageio.ImageIO;
import kh.cmt.support.QrImages;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;

/** The printed sheet: the QR on it reads back as the payload, as a PNG and on the A4 PDF page. */
class QrSheetTest {

    final QrSigner signer = new QrSigner("test-only-qr-signing-key-0123456789abcdef");
    final QrSheet sheet = new QrSheet();

    @Test
    void theQrOnTheSheetReadsBackAsThePayload() throws Exception {
        UUID school = UUID.randomUUID();
        UUID gate = UUID.randomUUID();
        String payload = signer.sign(school, gate, 3, QrSigner.newSalt()).payload();
        QrSheet.Content content = new QrSheet.Content(payload, "CMT School", null, "Main gate", "Main campus", "Main gate · QR v3 · 24 Oct 2026");

        byte[] png = sheet.png(content);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(image.getWidth()).isEqualTo(QrSheet.WIDTH);
        assertThat(image.getHeight()).isEqualTo(QrSheet.HEIGHT);
        assertThat(read(image)).isEqualTo(payload);
        Path sample = Path.of("build", "qr-sheet-sample.png");
        Files.createDirectories(sample.getParent());
        Files.write(sample, png);   // to look at by eye (Khmer shaping)

        byte[] pdf = sheet.pdf(content);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
            assertThat(doc.getPage(0).getMediaBox().getWidth()).isEqualTo(PDRectangle.A4.getWidth());
            BufferedImage page = new PDFRenderer(doc).renderImageWithDPI(0, 100);
            assertThat(read(page)).isEqualTo(payload);
        }
    }

    @Test
    void payloadsAreSignedAndParsed() {
        UUID school = UUID.randomUUID();
        UUID gate = UUID.randomUUID();
        String salt = QrSigner.newSalt();
        QrSigner.Signed signed = signer.sign(school, gate, 7, salt);
        assertThat(signed.payload()).matches("cmt://gate/" + gate + "\\?v=7&sig=[A-Za-z0-9_-]{22}");
        assertThat(signed.sigHash()).hasSize(64).doesNotContain(signed.sig());
        QrSigner.Parsed parsed = QrSigner.parse(signed.payload()).orElseThrow();
        assertThat(parsed.gateId()).isEqualTo(gate);
        assertThat(parsed.version()).isEqualTo(7);
        assertThat(signer.matches(school, gate, 7, salt, signed.sigHash(), parsed.sig())).isTrue();
        // same QR again from the stored salt (reprint); another school / version / key does not match
        assertThat(signer.sign(school, gate, 7, salt).payload()).isEqualTo(signed.payload());
        assertThat(signer.matches(UUID.randomUUID(), gate, 7, salt, signed.sigHash(), parsed.sig())).isFalse();
        assertThat(signer.matches(school, gate, 8, salt, signed.sigHash(), parsed.sig())).isFalse();
        assertThat(new QrSigner("another-key-0123456789abcdef-0123456789").matches(school, gate, 7, salt, signed.sigHash(), parsed.sig()))
                .isFalse();
        // not ours
        assertThat(QrSigner.parse("https://example.com")).isEmpty();
        assertThat(QrSigner.parse("cmt://gate/not-a-uuid?v=1&sig=AAAAAAAAAAAAAAAAAAAAAA")).isEmpty();
        assertThat(QrSigner.parse("cmt://gate/" + gate + "?v=0&sig=AAAAAAAAAAAAAAAAAAAAAA")).isEmpty();
        assertThat(QrSigner.parse("cmt://gate/" + gate + "?v=1&sig=short")).isEmpty();
        assertThat(QrSigner.parse(null)).isEmpty();
    }

    private static String read(BufferedImage image) throws Exception {
        return QrImages.read(image);
    }
}
