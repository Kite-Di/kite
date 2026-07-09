# The runtime discovers the inspector through ServiceLoader (debug builds only —
# release builds use :kite:inspector-noop, which registers nothing).
-keep class com.kite.di.inspector.InspectorHookImpl { *; }
