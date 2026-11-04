package org.conscrypt;

import java.security.KeyManagementException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.conscrypt.metrics.CertificateTransparencyVerificationReason;

/**
 * Version-controlled adapter for the official, unmodified Conscrypt 2.8.0 JAR/AAR.
 *
 * This intentionally shares org.conscrypt to bind package-private SSLParametersImpl and @Internal
 * policy types. It is NOT a supported Conscrypt public API. Recompile and rerun independent-peer
 * tests before any provider upgrade; preserve this class/method against shrinking on Android.
 * No private reflection, native patch, global policy, or global provider registration is used here.
 */
public final class NativeEchPolicyAdapter {
    private NativeEchPolicyAdapter() {}

    public static X509TrustManager requireEch(
            X509TrustManager delegate, byte[] configList, String hostname)
            throws KeyManagementException, SSLException {
        Conscrypt.checkAvailability();
        Conscrypt.Version version = Conscrypt.version();
        if (version == null || version.major() != 2 || version.minor() != 8 || version.patch() != 0) {
            throw new SSLException("NativeEchPolicyAdapter requires Conscrypt 2.8.0");
        }
        if (delegate == null || configList == null || hostname == null) {
            throw new SSLException("Missing required ECH adapter input");
        }
        RequiredTrustManager manager = new RequiredTrustManager(delegate);
        // Check that this exact binary actually consumes the attached policy. Fail before any
        // ClientHello if the provider's internal hook changes or cannot invoke the manager method.
        SSLParametersImpl parameters = new SSLParametersImpl(
                new KeyManager[0], new TrustManager[] {manager}, null,
                new ClientSessionContext(), new ServerSessionContext(), new String[] {"TLSv1.3"});
        parameters.setEchConfigList(configList.clone());
        EchOptions options = parameters.getEchOptions(hostname);
        if (parameters.getPolicy().getDomainEncryptionMode(hostname) != DomainEncryptionMode.REQUIRED
                || options == null || !options.isFailClosed() || options.isGreaseEnabled()
                || !Arrays.equals(configList, options.getConfigList())) {
            throw new SSLException("Conscrypt REQUIRED ECH policy hook unavailable");
        }
        return manager;
    }

    /**
     * Public visibility is needed by the provider's existing getNetworkSecurityPolicy() lookup.
     * Conscrypt itself uses public-method reflection for this platform hook; we do not reflect.
     * Chain checks are delegated unchanged to the caller's strict trust manager.
     */
    public static final class RequiredTrustManager implements X509TrustManager {
        private final X509TrustManager delegate;
        private final NetworkSecurityPolicy policy = new NetworkSecurityPolicy() {
            @Override public boolean isCertificateTransparencyVerificationRequired(String hostname) {
                return false;
            }

            @Override public CertificateTransparencyVerificationReason
                    getCertificateTransparencyVerificationReason(String hostname) {
                return CertificateTransparencyVerificationReason.UNKNOWN;
            }

            @Override public DomainEncryptionMode getDomainEncryptionMode(String hostname) {
                return DomainEncryptionMode.REQUIRED;
            }
        };

        private RequiredTrustManager(X509TrustManager delegate) {
            this.delegate = delegate;
        }

        public NetworkSecurityPolicy getNetworkSecurityPolicy() {
            return policy;
        }

        @Override public X509Certificate[] getAcceptedIssuers() {
            return delegate.getAcceptedIssuers().clone();
        }

        @Override public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            delegate.checkClientTrusted(chain, authType);
        }

        @Override public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            delegate.checkServerTrusted(chain, authType);
        }
    }
}
