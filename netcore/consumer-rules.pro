# Conscrypt 2.8.0 invokes this public TrustManager hook by name inside the provider.
# Future Android packaging MUST include this file as consumerProguardFiles.
-keep class org.conscrypt.NativeEchPolicyAdapter {
    public static javax.net.ssl.X509TrustManager requireEch(javax.net.ssl.X509TrustManager, byte[], java.lang.String);
}
-keep class org.conscrypt.NativeEchPolicyAdapter$RequiredTrustManager {
    public org.conscrypt.NetworkSecurityPolicy getNetworkSecurityPolicy();
}
