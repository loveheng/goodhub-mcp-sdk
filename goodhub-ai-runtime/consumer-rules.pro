# GoodHub AI Runtime 消费方混淆规则：
# client 门面经类名反射触达 runtime 自注册（零显式初始化），类名/入口须保。
-keep class com.zzh.goodhub.ai.runtime.GoodHubAiRuntime { *; }
-keep class com.zzh.goodhub.ai.runtime.AiTransportService { *; }
-keep class com.zzh.goodhub.ai.runtime.AiFailureException { *; }
# client API 面（业务层反射/序列化场景兜底）
-keep class com.zzh.goodhub.ai.** { *; }
