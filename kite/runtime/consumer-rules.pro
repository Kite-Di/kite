# Kite.init() locates the KSP-generated registry aggregate by name (one-time,
# cold-path reflection). Everything it references is then retained by normal shrinking.
-keep class com.kite.di.generated.MergedRegistry { *; }

# Runtime tracing exists for the debug board and has no consumer in a release app:
# the inspector artifact is debugImplementation-only, so nothing ever raises this
# flag. Telling R8 to assume it false turns every `if (GraphEvents.tracing)` into
# dead code — the clock reads, the event allocations, the scope paths they carry
# and the GraphEvent classes themselves are all removed from the release build.
# (Debug builds are not minified, so the inspector keeps working.)
-assumevalues class com.kite.di.runtime.observe.GraphEvents {
    boolean tracing return false;
}
