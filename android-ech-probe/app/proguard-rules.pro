# Manifest entry points and our public result method must remain invocable after R8.
-keep class com.dsha.nativeechtest.EchProbeInstrumentation { *; }
-keep class com.dsha.nativeechtest.EchProbeActivity { *; }
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,Signature
# Keep the outer diagnostic exception class identifiable alongside the already-kept provider causes.
-keep class com.dsha.sidecar.net.proxy.ech.EchConnectionException { *; }
