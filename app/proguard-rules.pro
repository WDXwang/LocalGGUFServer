# llama.cpp JNI 通过 JNI 回调与 Java 交互
-keepclasseswithmembernames class com.workbuddy.localmodel.llm.LlamaBridge {
    native <methods>;
}
-keep interface com.workbuddy.localmodel.llm.LlamaBridge$TokenCallback { *; }
-keep class com.workbuddy.localmodel.llm.LlamaBridge$TokenCallback { *; }
