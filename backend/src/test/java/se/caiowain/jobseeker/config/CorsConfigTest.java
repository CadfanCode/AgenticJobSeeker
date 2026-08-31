package se.caiowain.jobseeker.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import se.caiowain.jobseeker.AbstractIntegrationTest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The dev server's port is not ours to predict.
 *
 * <p>Vite takes 5173 when it is free and silently moves to 5174, 5175 and onward when it is
 * not — another project's dev server is enough to shift it. Pinning a single port meant every
 * write from the browser failed with 403 while reading worked, because browsers send an
 * {@code Origin} header on POST, PUT and DELETE even for a same-origin request, and omit it
 * on a same-origin GET. The result looked like a broken save button rather than a CORS rule.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CorsConfigTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void allowsTheDefaultVitePort() throws Exception {
        mvc.perform(get("/api/jobs").header("Origin", "http://localhost:5173"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    @Test
    void allowsWhicheverPortViteActuallyPicked() throws Exception {
        // The bug: vite fell back to 5174 because another project held 5173, and every save
        // in the UI started returning 403.
        mvc.perform(get("/api/jobs").header("Origin", "http://localhost:5174"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5174"));
    }

    @Test
    void allowsWritesFromThatPortToo() throws Exception {
        // A GET passing proves little on its own: the methods that actually broke are the
        // ones a browser attaches an Origin to unconditionally.
        mvc.perform(options("/api/preferences")
                        .header("Origin", "http://localhost:5174")
                        .header("Access-Control-Request-Method", "PUT"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5174"));

        mvc.perform(options("/api/profile/upload")
                        .header("Origin", "http://localhost:5174")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk());
    }

    @Test
    void allowsTheLoopbackAddressAsWellAsTheName() throws Exception {
        mvc.perform(get("/api/jobs").header("Origin", "http://127.0.0.1:5174"))
                .andExpect(status().isOk());
    }

    @Test
    void stillRejectsAnOriginThatIsNotLocal() throws Exception {
        // The wildcard is over ports on the loopback host, not over hosts. A page served from
        // somewhere else on the internet must not be able to drive this API.
        mvc.perform(get("/api/jobs").header("Origin", "https://evil.example.com"))
                .andExpect(status().isForbidden());
    }
}
