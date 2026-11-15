# Called from native code.
-keep interface com.bhasha.engine.llm.TokenListener { *; }
-keepclasseswithmembernames class com.bhasha.engine.llm.LlmEngine { native <methods>; }
