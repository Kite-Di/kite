# Kite.init() locates the KSP-generated registry aggregate by name (one-time,
# cold-path reflection). Everything it references is then retained by normal shrinking.
-keep class com.kite.di.generated.MergedRegistry { *; }
