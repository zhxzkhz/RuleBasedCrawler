package com.zhhz.spider.util

object ImageFormatDetector {
    /**
     * 💡 终极全格式探测器：支持 JPEG, PNG, WebP, GIF, BMP, ICO, HEIF/HEIC
     * 100% 纯 Kotlin 编写，无任何平台依赖，O(1) 毫秒级极速解析！
     */
    fun detectFormat(bytes: ByteArray): String {
        if (bytes.size < 12) return "jpg"

        // 1. JPEG: FF D8 FF
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
            return "jpg"
        }

        // 2. PNG: 89 50 4E 47
        if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) {
            return "png"
        }

        // 3. WebP: "RIFF" (0..3) & "WEBP" (8..11)
        if (bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
            bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() && bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte()
        ) {
            return "webp"
        }

        // 4. GIF: "GIF8" (47 49 46 38)
        if (bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() && bytes[3] == 0x38.toByte()) {
            return "gif"
        }

        // 5. BMP: "BM" (42 4D)
        if (bytes[0] == 0x42.toByte() && bytes[1] == 0x4D.toByte()) {
            return "bmp"
        }

        // 6. ICO: 00 00 01/02 00 (Windows 图标/光标)
        if (bytes[0] == 0x00.toByte() && bytes[1] == 0x00.toByte() &&
            (bytes[2] == 0x01.toByte() || bytes[2] == 0x02.toByte()) &&
            bytes[3] == 0x00.toByte()
        ) {
            return "ico"
        }

        // 7. HEIF / HEIC: 前4字节大小不固定，但第4-7字节为 "ftyp" (66 74 79 70)，且第8-11字节为 "heic"/"heif"/"mif1"/"msf1"
        if (bytes[4] == 0x66.toByte() && bytes[5] == 0x74.toByte() && bytes[6] == 0x79.toByte() && bytes[7] == 0x70.toByte()) {
            // "heic" = 68 65 69 63, "heif" = 68 65 69 66
            // "mif1" = 6D 69 66 31, "msf1" = 6D 73 66 31
            val b8 = bytes[8]
            val b9 = bytes[9]
            val b10 = bytes[10]
            val b11 = bytes[11]
            if ((b8 == 0x68.toByte() && b9 == 0x65.toByte() && b10 == 0x69.toByte() && (b11 == 0x63.toByte() || b11 == 0x66.toByte())) ||
                (b8 == 0x6D.toByte() && b10 == 0x66.toByte() && b11 == 0x31.toByte() && (b9 == 0x69.toByte() || b9 == 0x73.toByte()))
            ) {
                return "heif"
            }
        }

        return "jpg" // 默认保底
    }
}