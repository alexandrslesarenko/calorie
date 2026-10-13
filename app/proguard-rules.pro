# Project rules on top of the ones the libraries ship.

# The Anthropic SDK rules are not enough: with them a messages.create request fails
# with "JsonMissing cannot be serialized" (Jackson builds the field filters and
# serializers by reflection). Keep the SDK and Jackson whole; the rest still shrinks.
-keep class com.anthropic.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**
# Structured outputs code in the SDK (unused here) refers to JDK reflection types
# that Android does not have.
-dontwarn java.lang.reflect.AnnotatedType
-dontwarn java.lang.reflect.AnnotatedParameterizedType
