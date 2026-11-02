package org.conscrypt;

import static org.junit.Assert.*;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.Test;

/** Direct package API checks against the actual 2.8.0 binary, without reflective access. */
public class NativeEchPolicyAdapterTest {
    private static final byte[] OPAQUE_CONFIG = {0, 4, 1, 2, 0, 0};

    private static SSLParametersImpl parameters(X509TrustManager trust) throws Exception {
        return new SSLParametersImpl(new KeyManager[0], new TrustManager[] {trust}, null,
                new ClientSessionContext(), new ServerSessionContext(), new String[] {"TLSv1.3"});
    }

    @Test public void defaultOpenJdkPolicyDoesNotEnableEchEvenWithConfig() throws Exception {
        SSLParametersImpl parameters = parameters(new RejectAll());
        parameters.setEchConfigList(OPAQUE_CONFIG.clone());
        assertEquals(DomainEncryptionMode.UNKNOWN, parameters.getPolicy().getDomainEncryptionMode("inner.example"));
        assertNull(parameters.getEchOptions("inner.example"));
    }

    @Test public void echOptionsTwoArgumentConstructorDoesNotFailClosed() {
        EchOptions options = new EchOptions(OPAQUE_CONFIG.clone(), true);
        assertFalse(options.isFailClosed());
        assertTrue(options.isGreaseEnabled());
    }

    @Test public void adapterPolicyIsRequiredAndConsumedByActualSslParameters() throws Exception {
        byte[] config = OPAQUE_CONFIG.clone();
        X509TrustManager trust = NativeEchPolicyAdapter.requireEch(new RejectAll(), config, "inner.example");
        SSLParametersImpl parameters = parameters(trust);
        parameters.setEchConfigList(config.clone());
        EchOptions options = parameters.getEchOptions("inner.example");
        assertEquals(DomainEncryptionMode.REQUIRED, parameters.getPolicy().getDomainEncryptionMode("inner.example"));
        assertTrue(options.isFailClosed());
        assertFalse(options.isGreaseEnabled());
        assertArrayEquals(config, options.getConfigList());
    }

    @Test public void requiredPolicyRejectsMissingConfig() throws Exception {
        X509TrustManager trust = NativeEchPolicyAdapter.requireEch(new RejectAll(), OPAQUE_CONFIG, "inner.example");
        SSLParametersImpl parameters = parameters(trust);
        assertThrows(SSLException.class, () -> parameters.getEchOptions("inner.example"));
    }

    @Test public void adapterCannotReplaceStrictChainFailureWithSuccess() throws Exception {
        X509TrustManager trust = NativeEchPolicyAdapter.requireEch(new RejectAll(), OPAQUE_CONFIG, "inner.example");
        assertThrows(CertificateException.class,
                () -> trust.checkServerTrusted(new X509Certificate[0], "GENERIC"));
        assertThrows(CertificateException.class,
                () -> trust.checkClientTrusted(new X509Certificate[0], "GENERIC"));
    }

    @Test public void adapterRejectsMissingInputs() {
        assertThrows(SSLException.class, () -> NativeEchPolicyAdapter.requireEch(null, OPAQUE_CONFIG, "inner.example"));
        assertThrows(SSLException.class, () -> NativeEchPolicyAdapter.requireEch(new RejectAll(), null, "inner.example"));
        assertThrows(SSLException.class, () -> NativeEchPolicyAdapter.requireEch(new RejectAll(), OPAQUE_CONFIG, null));
    }

    private static final class RejectAll implements X509TrustManager {
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("Deliberate chain failure");
        }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("Deliberate chain failure");
        }
    }
}
