//import java.net.URL 旧版用的
import java.net.URI //新语法需要
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit //计算geofile下载时间需要

plugins {
    kotlin("android")
    kotlin("kapt")
    id("com.android.application")
}

dependencies {
    compileOnly(project(":hideapi"))

    implementation(project(":core"))
    implementation(project(":service"))
    implementation(project(":design"))
    implementation(project(":common"))

    implementation(libs.kotlin.coroutine)
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.coordinator)
    implementation(libs.androidx.recyclerview)
    implementation(libs.google.material)
    implementation(libs.quickie.bundled)
    implementation(libs.androidx.activity.ktx)
}

tasks.getByName("clean", type = Delete::class) {
    delete(file("release"))
}

val geoFilesDownloadDir = "src/main/assets"

//task("downloadGeoFiles") { 改成新语法
tasks.register("downloadGeoFiles") {

    val geoFilesUrls = mapOf(
        "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geoip.metadb" to "geoip.metadb",
        "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geosite.dat" to "geosite.dat",
        // "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/country.mmdb" to "country.mmdb",
        "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/GeoLite2-ASN.mmdb" to "ASN.mmdb",
        "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/BundleMRS.7z" to "BundleMRS.7z",
    )

    doLast {
        if (project.hasProperty("skipGeo")) {
            println("Skipping downloadGeoFiles as requested by skipGeo property.")
            return@doLast
        }

        geoFilesUrls.forEach { (downloadUrl, outputFileName) ->
            //val url = URL(downloadUrl) 更新成新语法
            val url = URI(downloadUrl).toURL()

            val outputPath = file("$geoFilesDownloadDir/$outputFileName")
            val oneMonthInMillis = TimeUnit.DAYS.toMillis(30)  // 定義一個月的毫秒數（以 30 天計算）
            val fileAge = System.currentTimeMillis() - outputPath.lastModified()

            if (outputPath.exists() && outputPath.length() > 0 && fileAge <= oneMonthInMillis) { // 如果檔案存在、大小大於 0，且下載時間在一個月內，才跳過下載
                println("$outputFileName already exists and is up to date, skipping download.")
                return@forEach
            }

            outputPath.parentFile.mkdirs()
            url.openStream().use { input ->
                Files.copy(input, outputPath.toPath(), StandardCopyOption.REPLACE_EXISTING)
                println("$outputFileName downloaded to $outputPath")
            }
        }
    }
}

afterEvaluate {
    val downloadGeoFilesTask = tasks["downloadGeoFiles"]

    tasks.forEach {
        if (it.name.startsWith("assemble")) {
            it.dependsOn(downloadGeoFilesTask)
        }
    }
}

tasks.getByName("clean", type = Delete::class) {
    delete(file(geoFilesDownloadDir))
}

configure<com.android.build.api.dsl.ApplicationExtension> {
    androidResources {
        localeFilters.addAll(listOf("en", "zh-rTW", "zh"))  // 最新的語系過濾 API
    }
}