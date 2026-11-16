package kh.cmt.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import kh.cmt.support.ApiTest;
import kh.cmt.support.TestJwt;
import kh.cmt.support.TestJwt.Caller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Step 4.3 "done when": documents show on the profile; expired ones are flagged. The uploads go through the real
 * flow — upload link, HTTP PUT straight to object storage (a throw-away MinIO), confirm — and downloads through the
 * short-lived links.
 */
@ApiTest
class DocumentsTest {

    private static final String FILES = "/api/v1/files";
    private static final String PEOPLE = "/api/v1/admin/people";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired Clock clock;

    private UUID school;
    private Caller admin;
    private JsonNode person;

    @BeforeEach
    void schoolWithAPerson() throws Exception {
        school = UUID.randomUUID();
        jdbc.sql("insert into school (id, code, name_en, created_at, updated_at) values (:id, :c, 'Files school', now(), now())")
                .param("id", school).param("c", "F" + school.toString().substring(0, 8)).update();
        admin = TestJwt.webUser("super_admin", school);
        person = body(mvc.perform(post(PEOPLE).header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":{\"km\":\"សុខា\",\"en\":\"Sokha Lim\"},\"role\":\"teacher\"}")).andExpect(status().isCreated()));
    }

    // ------------------------------------------------------------------ the "done when" check

    @Test
    void documentsShowOnTheProfileAndExpiredOnesAreFlagged() throws Exception {
        LocalDate today = LocalDate.now(clock);
        UUID contract = upload(admin, "DOCUMENT", "contract.pdf", "application/pdf", pdf());
        UUID degree = upload(admin, "DOCUMENT", "degree.jpg", "image/jpeg", jpeg(800, 600));
        UUID health = upload(admin, "DOCUMENT", "health.png", "image/png", png(640, 480));
        addDocument(person, "CONTRACT", contract, today.minusYears(1), today.minusDays(1)).andExpect(status().isCreated());
        addDocument(person, "DEGREE", degree, today.minusYears(5), null).andExpect(status().isCreated());
        addDocument(person, "HEALTH_CERT", health, today.minusMonths(11), today.plusDays(10)).andExpect(status().isCreated());

        JsonNode profile = body(mvc.perform(get(PEOPLE + "/" + id(person)).header("Authorization", admin.bearer())));
        assertThat(profile.get("documents")).hasSize(3);
        assertThat(profile.get("expiredDocuments").asInt()).isEqualTo(1);
        Map<String, JsonNode> byType = byType(profile.get("documents"));
        assertThat(byType.get("CONTRACT").get("status").asText()).isEqualTo("EXPIRED");
        assertThat(byType.get("CONTRACT").get("expired").asBoolean()).isTrue();
        assertThat(byType.get("HEALTH_CERT").get("status").asText()).isEqualTo("EXPIRING");
        assertThat(byType.get("HEALTH_CERT").get("expiresInDays").asInt()).isEqualTo(10);
        assertThat(byType.get("DEGREE").get("status").asText()).isEqualTo("VALID");
        assertThat(byType.get("CONTRACT").get("file").get("name").asText()).isEqualTo("contract.pdf");
        assertThat(byType.get("CONTRACT").get("file").get("hasThumbnail").asBoolean()).isFalse();
        assertThat(byType.get("DEGREE").get("file").get("hasThumbnail").asBoolean()).isTrue();

        // the People list flags the person too, and the documents have their own list
        JsonNode list = body(mvc.perform(get(PEOPLE).header("Authorization", admin.bearer())));
        assertThat(list.get("items").get(0).get("expiredDocuments").asInt()).isEqualTo(1);
        assertThat(body(mvc.perform(get(PEOPLE + "/" + id(person) + "/documents").header("Authorization", admin.bearer())))).hasSize(3);

        // and they open: the same bytes come back
        assertThat(download(admin, contract, false)).isEqualTo(pdf());
        byte[] thumbnail = download(admin, degree, true);
        BufferedImage thumb = ImageIO.read(new ByteArrayInputStream(thumbnail));
        assertThat(Math.max(thumb.getWidth(), thumb.getHeight())).isEqualTo(320);
    }

    // ------------------------------------------------------------------ uploads are checked

    @Test
    void uploadsAreCheckedForTypeSizeAndContent() throws Exception {
        requestUpload(admin, "DOCUMENT", "virus.exe", "application/x-msdownload", 100)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FILE_TYPE"));
        requestUpload(admin, "DOCUMENT", "big.pdf", "application/pdf", 6 * 1024 * 1024)
                .andExpect(jsonPath("$.code").value("FILE_TOO_LARGE")).andExpect(jsonPath("$.maxBytes").value(5 * 1024 * 1024));
        requestUpload(admin, "PHOTO", "photo.pdf", "application/pdf", 100).andExpect(jsonPath("$.code").value("FILE_TYPE"));
        requestUpload(admin, "SELFIE", "x.png", "image/png", 100).andExpect(status().isBadRequest());

        // confirmed before uploading
        JsonNode link = body(requestUpload(admin, "DOCUMENT", "late.pdf", "application/pdf", 100).andExpect(status().isOk()));
        confirm(admin, link.get("fileId").asText()).andExpect(jsonPath("$.code").value("FILE_NOT_UPLOADED"));

        // says PNG, is a PDF
        UUID lying = uploadOnly(admin, "DOCUMENT", "photo.png", "image/png", pdf());
        confirm(admin, lying.toString()).andExpect(jsonPath("$.code").value("FILE_TYPE"));

        // looks like a PNG, cannot be read as one
        byte[] broken = Arrays.copyOf(png(10, 10), 40);
        UUID brokenId = uploadOnly(admin, "DOCUMENT", "broken.png", "image/png", broken);
        confirm(admin, brokenId.toString()).andExpect(jsonPath("$.code").value("FILE_INVALID"));

        // bigger than declared and than the limit
        byte[] tooBig = Arrays.copyOf(png(10, 10), 2 * 1024 * 1024 + 10);
        UUID bigId = uploadOnly(admin, "PHOTO", "huge.png", "image/png", tooBig);
        confirm(admin, bigId.toString()).andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));

        // only the uploader confirms
        UUID mine = uploadOnly(admin, "DOCUMENT", "mine.pdf", "application/pdf", pdf());
        confirm(TestJwt.webUser("super_admin", school), mine.toString()).andExpect(status().isNotFound());
    }

    @Test
    void largeImagesAreScaledDown() throws Exception {
        UUID big = upload(admin, "DOCUMENT", "scan.png", "image/png", png(3000, 1500));
        JsonNode file = body(confirm(admin, big.toString()));   // confirming again changes nothing
        assertThat(file.get("status").asText()).isEqualTo("READY");
        assertThat(file.get("width").asInt()).isEqualTo(2000);
        assertThat(file.get("height").asInt()).isEqualTo(1000);
        BufferedImage stored = ImageIO.read(new ByteArrayInputStream(download(admin, big, false)));
        assertThat(stored.getWidth()).isEqualTo(2000);
    }

    @Test
    void aFileServesOneRecordOfItsPurpose() throws Exception {
        UUID photo = upload(admin, "PHOTO", "me.jpg", "image/jpeg", jpeg(400, 400));
        addDocument(person, "CV", photo, null, null).andExpect(jsonPath("$.code").value("FILE_NOT_READY"));   // a photo, not a document
        UUID cv = upload(admin, "DOCUMENT", "cv.pdf", "application/pdf", pdf());
        addDocument(person, "CV", cv, null, null).andExpect(status().isCreated());
        addDocument(person, "CV", cv, null, null).andExpect(jsonPath("$.code").value("FILE_IN_USE"));
        JsonNode link = body(requestUpload(admin, "DOCUMENT", "x.pdf", "application/pdf", 10));
        addDocument(person, "CV", UUID.fromString(link.get("fileId").asText()), null, null)
                .andExpect(jsonPath("$.code").value("FILE_NOT_READY"));   // not confirmed
        addDocument(person, "PASSPORT", cv, null, null).andExpect(status().isBadRequest());
        addDocument(person, "CV", upload(admin, "DOCUMENT", "y.pdf", "application/pdf", pdf()), LocalDate.of(2020, 5, 1),
                LocalDate.of(2020, 1, 1)).andExpect(jsonPath("$.errors[0].rule").value("BeforeIssue"));
    }

    // ------------------------------------------------------------------ who may see what

    @Test
    void documentsFollowTheSensitiveFieldsAndTheScope() throws Exception {
        UUID scanFront = upload(admin, "DOCUMENT", "id-front.jpg", "image/jpeg", jpeg(300, 200));
        UUID contract = upload(admin, "DOCUMENT", "contract.pdf", "application/pdf", pdf());
        addDocument(person, "NATIONAL_ID_FRONT", scanFront, null, LocalDate.now(clock).plusYears(5)).andExpect(status().isCreated());
        addDocument(person, "CONTRACT", contract, null, null).andExpect(status().isCreated());

        // HR: national ID & documents yes, national ID scans no
        Caller hr = TestJwt.webUser("hr", school);
        Map<String, JsonNode> hrSees = byType(body(mvc.perform(get(PEOPLE + "/" + id(person) + "/documents").header("Authorization", hr.bearer()))));
        assertThat(hrSees.get("CONTRACT").get("locked").asBoolean()).isFalse();
        assertThat(hrSees.get("NATIONAL_ID_FRONT").get("locked").asBoolean()).isTrue();
        assertThat(hrSees.get("NATIONAL_ID_FRONT").has("file")).isFalse();
        assertThat(hrSees.get("NATIONAL_ID_FRONT").get("status").asText()).isEqualTo("VALID");   // the expiry still shows
        mvc.perform(get(FILES + "/" + contract + "/url").header("Authorization", hr.bearer())).andExpect(status().isOk());
        mvc.perform(get(FILES + "/" + scanFront + "/url").header("Authorization", hr.bearer())).andExpect(status().isNotFound());
        // nor can HR add or remove a scan
        addDocument(hr, person, "NATIONAL_ID_BACK", upload(hr, "DOCUMENT", "back.jpg", "image/jpeg", jpeg(10, 10)), null, null)
                .andExpect(status().isForbidden());

        // Viewer: no People edit, neither group
        Caller viewer = TestJwt.webUser("viewer", school);
        assertThat(byType(body(mvc.perform(get(PEOPLE + "/" + id(person) + "/documents").header("Authorization", viewer.bearer()))))
                .get("CONTRACT").get("locked").asBoolean()).isTrue();
        mvc.perform(get(FILES + "/" + contract + "/url").header("Authorization", viewer.bearer())).andExpect(status().isNotFound());

        // the person themselves (app) may open their own; somebody else's app may not; another school sees nothing
        Caller self = TestJwt.withPerson("app", "teacher", school, UUID.randomUUID(), UUID.fromString(id(person)));
        mvc.perform(get(FILES + "/" + contract + "/url").header("Authorization", self.bearer())).andExpect(status().isOk());
        Caller other = TestJwt.withPerson("app", "staff", school, UUID.randomUUID(), UUID.randomUUID());
        mvc.perform(get(FILES + "/" + contract + "/url").header("Authorization", other.bearer())).andExpect(status().isNotFound());
        mvc.perform(get(FILES + "/" + contract + "/url").header("Authorization", TestJwt.webUser("super_admin").bearer()))
                .andExpect(status().isNotFound());

        // a file no record uses yet: only its uploader
        UUID loose = upload(admin, "DOCUMENT", "loose.pdf", "application/pdf", pdf());
        mvc.perform(get(FILES + "/" + loose + "/url").header("Authorization", admin.bearer())).andExpect(status().isOk());
        mvc.perform(get(FILES + "/" + loose + "/url").header("Authorization", hr.bearer())).andExpect(status().isNotFound());
    }

    @Test
    void removingADocumentRemovesItsFileAndDocumentsKeepAPersonFromBeingDeletedForever() throws Exception {
        UUID cv = upload(admin, "DOCUMENT", "cv.pdf", "application/pdf", pdf());
        JsonNode doc = body(addDocument(person, "CV", cv, null, null));
        mvc.perform(post(PEOPLE + "/" + id(person) + "/inactivate").header("Authorization", admin.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"MISTAKE\"}")).andExpect(status().isNoContent());
        mvc.perform(delete(PEOPLE + "/" + id(person)).header("Authorization", admin.bearer())).andExpect(jsonPath("$.code").value("IN_USE"));

        mvc.perform(post(PEOPLE + "/" + id(person) + "/restore").header("Authorization", admin.bearer())).andExpect(status().isOk());
        mvc.perform(delete(PEOPLE + "/" + id(person) + "/documents/" + doc.get("id").asText()).header("Authorization", admin.bearer()))
                .andExpect(status().isNoContent());
        assertThat(jdbc.sql("select count(*) from file where id = :f").param("f", cv).query(Integer.class).single()).isZero();
        mvc.perform(get(FILES + "/" + cv + "/url").header("Authorization", admin.bearer())).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ photos and the logo

    @Test
    void thePhotoIsSetOnThePersonAndByTheAppAndTheOldOneIsRemoved() throws Exception {
        UUID first = upload(admin, "PHOTO", "sokha.jpg", "image/jpeg", jpeg(600, 800));
        Map<String, Object> form = new HashMap<>(Map.of("name", Map.of("km", "តារា", "en", "Dara Chan"), "role", "staff",
                "photoFileId", first.toString()));
        JsonNode dara = body(mvc.perform(post(PEOPLE).header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(form))).andExpect(status().isCreated()));
        assertThat(dara.get("photoFileId").asText()).isEqualTo(first.toString());
        assertThat(dara.get("photo").get("width").asInt()).isEqualTo(600);

        // the app changes its own photo; the app may upload photos only
        Caller app = TestJwt.withPerson("app", "staff", school, UUID.randomUUID(), UUID.fromString(id(dara)));
        requestUpload(app, "DOCUMENT", "x.pdf", "application/pdf", 10).andExpect(status().isForbidden());
        UUID second = upload(app, "PHOTO", "new.png", "image/png", png(500, 500));
        JsonNode profile = body(mvc.perform(post("/api/v1/app/me/photo").header("Authorization", app.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"fileId\":\"" + second + "\"}")).andExpect(status().isOk()));
        assertThat(profile.get("photoFileId").asText()).isEqualTo(second.toString());
        assertThat(jdbc.sql("select count(*) from file where id = :f").param("f", first).query(Integer.class).single()).isZero();
        // office users with People view open it (thumbnail for the list)
        mvc.perform(get(FILES + "/" + second + "/url").param("thumbnail", "true").header("Authorization", TestJwt.webUser("viewer", school).bearer()))
                .andExpect(status().isOk());

        // Edit person keeps the photo when it is sent back, removes it when not
        JsonNode now = body(mvc.perform(get(PEOPLE + "/" + id(dara)).header("Authorization", admin.bearer())));
        Map<String, Object> edit = new HashMap<>(Map.of("name", Map.of("km", "តារា", "en", "Dara Chan"), "role", "staff",
                "version", now.get("version").asLong()));
        JsonNode cleared = body(mvc.perform(put(PEOPLE + "/" + id(dara)).header("Authorization", admin.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(edit))).andExpect(status().isOk()));
        assertThat(cleared.has("photoFileId")).isFalse();
    }

    @Test
    void theSchoolLogoIsForEverybodyOfTheSchool() throws Exception {
        UUID logo = upload(admin, "LOGO", "logo.png", "image/png", png(256, 256));
        JsonNode school0 = body(mvc.perform(get("/api/v1/admin/settings/school").header("Authorization", admin.bearer())));
        mvc.perform(put("/api/v1/admin/settings/school").header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":{\"en\":\"Files school\"},\"workingDays\":[1,2,3,4,5],\"timezone\":\"Asia/Phnom_Penh\","
                        + "\"logoFileId\":\"" + logo + "\",\"version\":" + school0.get("version") + "}"))
                .andExpect(status().isOk());
        Caller app = TestJwt.withPerson("app", "staff", school, UUID.randomUUID(), UUID.randomUUID());
        mvc.perform(get(FILES + "/" + logo + "/url").header("Authorization", app.bearer())).andExpect(status().isOk());
        mvc.perform(get(FILES + "/" + logo + "/url").header("Authorization", TestJwt.webUser("viewer", school).bearer()))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ helpers

    private ResultActions requestUpload(Caller caller, String purpose, String name, String type, long size) throws Exception {
        return mvc.perform(post(FILES + "/upload-url").header("Authorization", caller.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("purpose", purpose, "fileName", name, "contentType", type, "size", size))));
    }

    /** Upload link + PUT to storage, not confirmed. */
    private UUID uploadOnly(Caller caller, String purpose, String name, String type, byte[] bytes) throws Exception {
        JsonNode link = body(requestUpload(caller, purpose, name, type, Math.min(bytes.length, 1024)).andExpect(status().isOk()));
        HttpResponse<String> put = HTTP.send(HttpRequest.newBuilder(URI.create(link.get("uploadUrl").asText()))
                .header("Content-Type", type).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(put.statusCode()).as(put.body()).isEqualTo(200);
        return UUID.fromString(link.get("fileId").asText());
    }

    /** The whole upload: link, PUT, confirm. */
    private UUID upload(Caller caller, String purpose, String name, String type, byte[] bytes) throws Exception {
        UUID id = uploadOnly(caller, purpose, name, type, bytes);
        confirm(caller, id.toString()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"));
        return id;
    }

    private ResultActions confirm(Caller caller, String id) throws Exception {
        return mvc.perform(post(FILES + "/" + id + "/confirm").header("Authorization", caller.bearer()));
    }

    private byte[] download(Caller caller, UUID file, boolean thumbnail) throws Exception {
        JsonNode link = body(mvc.perform(get(FILES + "/" + file + "/url").param("thumbnail", String.valueOf(thumbnail))
                .header("Authorization", caller.bearer())).andExpect(status().isOk()));
        HttpResponse<byte[]> got = HTTP.send(HttpRequest.newBuilder(URI.create(link.get("url").asText())).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(got.statusCode()).isEqualTo(200);
        return got.body();
    }

    private ResultActions addDocument(JsonNode who, String type, UUID file, LocalDate issued, LocalDate expires) throws Exception {
        return addDocument(admin, who, type, file, issued, expires);
    }

    private ResultActions addDocument(Caller caller, JsonNode who, String type, UUID file, LocalDate issued, LocalDate expires)
            throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("type", type, "fileId", file.toString()));
        if (issued != null) {
            body.put("issueDate", issued.toString());
        }
        if (expires != null) {
            body.put("expiryDate", expires.toString());
        }
        return mvc.perform(post(PEOPLE + "/" + id(who) + "/documents").header("Authorization", caller.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private static Map<String, JsonNode> byType(JsonNode documents) {
        Map<String, JsonNode> found = new HashMap<>();
        documents.forEach(d -> found.put(d.get("type").asText(), d));
        return found;
    }

    private static String id(JsonNode node) {
        return node.get("id").asText();
    }

    private static byte[] pdf() {
        return "%PDF-1.4\n1 0 obj << /Type /Catalog >> endobj\ntrailer << /Root 1 0 R >>\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] png(int w, int h) throws Exception {
        return image(w, h, "png");
    }

    private static byte[] jpeg(int w, int h) throws Exception {
        return image(w, h, "jpg");
    }

    private static byte[] image(int w, int h, String format) throws Exception {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(194, 65, 12));
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
