// AGP 9.x 는 Kotlin 지원이 내장이라 org.jetbrains.kotlin.android 를 적용하지 않는다.
// Compose 컴파일러 플러그인 버전은 AGP 9.3.1 이 물고 있는 KGP(2.2.10)와 맞춘다.
plugins {
    id("com.android.application") version "9.3.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
