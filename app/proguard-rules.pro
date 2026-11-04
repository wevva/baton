# ── 바통 release 빌드 규칙 ─────────────────────────────────
# Pleos SDK: AIDL·Parcelable·리플렉션을 쓰므로 이름과 멤버를 유지 (TTS SDK는 자체 규칙이 없음)
-keep class ai.pleos.** { *; }
-keep interface ai.pleos.** { *; }
-dontwarn ai.pleos.**

# 보안: release 에서 디버그 로그 제거 (차량 상태가 시스템 로그에 남지 않도록). 오류(e)만 유지
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
}

# 바통 enum 이름 유지: 저장 형식(OpenItem.Type 이름)과 SDK 영역 이름 매칭(ROW_1_LEFT 등)에 쓰인다.
# (이 규칙이 없으면 난독화로 이름이 바뀌어 release 에서만 신호 구독·저장이 깨진다 — 2026-11-04 실측)
-keepclassmembernames enum com.baton.app.** { <fields>; }
-keepnames enum com.baton.app.**

# 크래시 분석용 줄 번호는 남기되 원래 소스 파일명은 숨김
-keepattributes LineNumberTable
-renamesourcefileattribute SourceFile

# Pleos SDK 내부의 Gson이 제네릭 타입(TypeToken)을 쓴다. 시그니처가 지워지면 기능 확인이 모두 실패한다 (2026-11-04 실측)
# 출처: Gson 공식 R8/ProGuard 안내 https://github.com/google/gson/blob/main/Troubleshooting.md#type-token-raw
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken
