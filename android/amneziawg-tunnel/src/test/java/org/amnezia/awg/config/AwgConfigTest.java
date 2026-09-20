package org.amnezia.awg.config;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class AwgConfigTest {
    private static final String CONFIG = "[Interface]\n"
        + "PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\n"
        + "Address = 10.0.0.2/32\nDNS = 1.1.1.1\n"
        + "Jc = 4\nJmin = 40\nJmax = 70\nS1 = 10\nS2 = 20\nS3 = 30\nS4 = 40\n"
        + "H1 = 100-200\nH2 = 300-400\nH3 = 500-600\nH4 = 700-800\n"
        + "I1 = <b 0xc000><r 10>\nI2 = <r 2>\nI3 = <r 3>\nI4 = <r 4>\nI5 = <r 5>\n"
        + "[Peer]\nPublicKey = AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=\n"
        + "AllowedIPs = 0.0.0.0/0, ::/0\nEndpoint = [2001:db8::1]:51820\nPersistentKeepalive = 25\n";

    private Config parse(String text) throws Exception {
        return Config.parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test public void awg31ParametersReachNativeCore() throws Exception {
        String nativeConfig = parse(CONFIG).toAwgUserspaceString();
        for (String field : new String[]{"jc=4", "jmin=40", "jmax=70", "s1=10", "s2=20", "s3=30", "s4=40",
                "h1=100-200", "h2=300-400", "h3=500-600", "h4=700-800",
                "i1=<b 0xc000><r 10>", "i2=<r 2>", "i3=<r 3>", "i4=<r 4>", "i5=<r 5>"}) {
            assertTrue(field, nativeConfig.contains(field + "\n"));
        }
    }

    @Test public void awg20DoesNotRequire31Sizes() throws Exception {
        String nativeConfig = parse(CONFIG.replace("S3 = 30\nS4 = 40\n", "")).toAwgUserspaceString();
        assertFalse(nativeConfig.contains("s3="));
        assertFalse(nativeConfig.contains("s4="));
        assertTrue(nativeConfig.contains("h1=100-200"));
        assertTrue(nativeConfig.contains("i5=<r 5>"));
    }

    @Test public void exclusionsMergeAndRoundTripWithoutLosingObfuscation() throws Exception {
        Config config = parse(CONFIG.replace("[Interface]\n", "[Interface]\nExcludedApplications = com.example.one\nExcludedApplications = com.example.two\n"));
        assertEquals(2, config.getInterface().getExcludedApplications().size());
        assertEquals(config.toAwgUserspaceString(), parse(config.toAwgQuickString()).toAwgUserspaceString());
    }
}
