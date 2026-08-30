package com.witvpn.vlee.crypto

/**
 * Runtime string decoder. Literals never appear in the dex.
 */
internal object VleeObf {

    private val cache = java.util.concurrent.ConcurrentHashMap<Int, String>()

    private fun decode(seed: Int, data: IntArray): String {
        cache[seed]?.let { return it }
        var s = (seed.toLong() and 0xFFFFFFFFL) or 1L
        val sb = java.lang.StringBuilder(data.size)
        for (b in data) {
            s = s * 6364136223846793005L + 1442695040888963407L
            sb.append(((b.toInt() xor ((s ushr 33).toInt())) and 0xFF).toChar())
        }
        val v = sb.toString()
        cache[seed] = v
        return v
    }

    private val D_AAD = intArrayOf(242, 43, 9, 99, 81, 43, 232, 113, 31, 16, 132, 162)
    private val S_AAD = 0x691BE037 // k=12
    private val D_KEY_LABEL = intArrayOf(235, 194, 51, 34, 55, 67)
    private val S_KEY_LABEL = 0x29D8E2A1 // k=6
    private val D_UA = intArrayOf(131, 171, 46, 108, 86, 181, 127, 28, 208, 246, 202, 20, 232, 240, 105, 187, 133, 65, 201, 38, 118, 118, 148, 160, 40, 126, 139, 150, 95, 126, 72, 209, 91, 116, 83, 61, 146, 119, 90, 61, 190, 135, 80, 44, 20, 65, 16, 254, 250, 17, 28, 18, 183, 73, 68, 125, 16, 3, 77, 117, 31, 184, 243, 215, 41, 150, 116, 132, 81, 6, 166, 31, 202, 26, 174, 6, 171, 33, 29, 247, 227, 175, 123, 180, 196, 96, 38, 138, 127, 196, 26, 53, 226, 79, 80, 82, 83, 254, 149, 82, 26, 77, 246, 50)
    private val S_UA = 0x24CA8BD5 // k=104
    private val D_PAYLOAD_TPL = intArrayOf(36, 221, 5, 112, 31, 27, 221, 167, 80, 127, 145, 79, 174, 126, 101, 2, 214, 63, 206, 227, 5, 177, 45, 248, 153, 2, 226, 17, 205, 115, 99, 56, 93, 70, 206, 7, 205, 121, 154, 80, 44, 63, 70, 51, 193, 211, 167, 133, 168, 219, 140, 60, 246, 122, 67, 185, 140, 63, 60, 109, 216, 191, 219, 165, 78, 221, 106, 114, 108, 149, 211, 207, 13, 93, 220, 65, 115, 38, 160, 195, 106, 129, 165, 32, 9, 78, 140, 197, 214, 61, 35, 201, 209, 96, 43, 79, 23, 242, 160, 157, 71, 135, 20, 208, 228, 145, 97, 141, 72, 4, 127, 254, 241, 14, 58, 88, 50, 248, 17, 102, 51, 5, 45, 191, 108, 93, 42, 60, 112, 6, 219, 141, 1, 122, 220, 246, 197, 197, 9, 223, 246, 123, 24, 97, 96, 224, 47, 64, 217, 209, 154, 207, 141, 154, 185, 48, 240, 63, 45, 94, 19, 126, 87, 132, 251, 17, 51, 214, 104, 97, 12, 149, 207, 115, 20, 87, 164, 183, 63, 127, 44, 139, 89, 74, 74, 218, 29, 221, 94, 30, 245, 190, 47, 35, 139)
    private val S_PAYLOAD_TPL = 0x680FCA18 // k=195
    private val D_TOK_SPLIT = intArrayOf(184, 247, 59, 0, 14, 46, 174)
    private val S_TOK_SPLIT = 0x5ADD1CBF // k=7
    private val D_TOK_DELAY = intArrayOf(88, 95, 0, 31, 127, 18, 226)
    private val S_TOK_DELAY = 0x5957BC6A // k=7
    private val D_T_CRLF = intArrayOf(89, 234, 4, 241, 205, 36)
    private val S_T_CRLF = 0x08A74168 // k=6
    private val D_T_CR = intArrayOf(58, 180, 246, 143)
    private val S_T_CR = 0x46945D06 // k=4
    private val D_T_LF = intArrayOf(152, 74, 28, 133)
    private val S_T_LF = 0x6132306C // k=4
    private val D_T_UA = intArrayOf(240, 51, 14, 12)
    private val S_T_UA = 0x4FD5E7DD // k=4
    private val D_T_METHOD = intArrayOf(70, 85, 156, 244, 118, 241, 167, 109)
    private val S_T_METHOD = 0x55EBDD60 // k=8
    private val D_T_PROTOCOL = intArrayOf(150, 30, 240, 253, 173, 173, 128, 65, 98, 63)
    private val S_T_PROTOCOL = 0x4C70616A // k=10
    private val D_T_HOST_PORT = intArrayOf(144, 10, 113, 146, 208, 39, 194, 100, 238, 141, 79)
    private val S_T_HOST_PORT = 0x569627DC // k=11
    private val D_T_SSH = intArrayOf(191, 204, 66, 160, 16)
    private val S_T_SSH = 0x5510AAC5 // k=5
    private val D_T_PORT = intArrayOf(205, 172, 60, 70, 19, 1)
    private val S_T_PORT = 0x3DFE2368 // k=6
    private val D_T_HOST = intArrayOf(233, 96, 209, 178, 142, 52)
    private val S_T_HOST = 0x47BAC0C8 // k=6
    private val D_T_SPLIT = intArrayOf(93, 55, 230, 145, 62, 193, 61)
    private val S_T_SPLIT = 0x6A9BCA8D // k=7
    private val D_PREFS = intArrayOf(156, 246, 52, 241, 194, 240, 137, 108)
    private val S_PREFS = 0x64ED552C // k=8
    private val D_KEY_CONFIG = intArrayOf(225, 183, 7, 228, 157, 218, 141, 82, 46, 71, 128, 15, 222, 76)
    private val S_KEY_CONFIG = 0x205E2B26 // k=14
    private val D_KEY_RUNNING = intArrayOf(135, 107, 194, 202, 3, 241, 83, 65, 68, 40)
    private val S_KEY_RUNNING = 0x0E10B378 // k=10
    private val D_HTTPS = intArrayOf(45, 151, 211, 166, 137)
    private val S_HTTPS = 0x2896B250 // k=5
    private val D_DEFAULT_USER = intArrayOf(222, 85)
    private val S_DEFAULT_USER = 0x5A910801 // k=2
    private val D_K_ID = intArrayOf(255, 52)
    private val S_K_ID = 0x77CDB36C // k=2
    private val D_K_IP = intArrayOf(243, 164)
    private val S_K_IP = 0x080DB5D1 // k=2
    private val D_K_SSH_PORT = intArrayOf(253, 186)
    private val S_K_SSH_PORT = 0x111897D7 // k=2
    private val D_K_SSH_USER = intArrayOf(49, 80)
    private val S_K_SSH_USER = 0x132CB6CE // k=2
    private val D_K_SSH_PASS = intArrayOf(211, 7)
    private val S_K_SSH_PASS = 0x0D632D45 // k=2
    private val D_K_PROXY_HOST = intArrayOf(100, 54)
    private val S_K_PROXY_HOST = 0x5737282E // k=2
    private val D_K_PROXY_PORT = intArrayOf(17, 153)
    private val S_K_PROXY_PORT = 0x713F3FD5 // k=2
    private val D_K_PROXY_SCHEME = intArrayOf(0, 238)
    private val S_K_PROXY_SCHEME = 0x2D1CF021 // k=2
    private val D_K_PAYLOAD = intArrayOf(34, 252)
    private val S_K_PAYLOAD = 0x70B04249 // k=2
    private val D_K_SNI = intArrayOf(61, 145)
    private val S_K_SNI = 0x369C7059 // k=2
    private val D_K_HOSTKEY = intArrayOf(191, 46)
    private val S_K_HOSTKEY = 0x50DCE53A // k=2

    val AAD: String get() = decode(S_AAD, D_AAD)
    val KEY_LABEL: String get() = decode(S_KEY_LABEL, D_KEY_LABEL)
    val UA: String get() = decode(S_UA, D_UA)
    val PAYLOAD_TPL: String get() = decode(S_PAYLOAD_TPL, D_PAYLOAD_TPL)
    val TOK_SPLIT: String get() = decode(S_TOK_SPLIT, D_TOK_SPLIT)
    val TOK_DELAY: String get() = decode(S_TOK_DELAY, D_TOK_DELAY)
    val T_CRLF: String get() = decode(S_T_CRLF, D_T_CRLF)
    val T_CR: String get() = decode(S_T_CR, D_T_CR)
    val T_LF: String get() = decode(S_T_LF, D_T_LF)
    val T_UA: String get() = decode(S_T_UA, D_T_UA)
    val T_METHOD: String get() = decode(S_T_METHOD, D_T_METHOD)
    val T_PROTOCOL: String get() = decode(S_T_PROTOCOL, D_T_PROTOCOL)
    val T_HOST_PORT: String get() = decode(S_T_HOST_PORT, D_T_HOST_PORT)
    val T_SSH: String get() = decode(S_T_SSH, D_T_SSH)
    val T_PORT: String get() = decode(S_T_PORT, D_T_PORT)
    val T_HOST: String get() = decode(S_T_HOST, D_T_HOST)
    val T_SPLIT: String get() = decode(S_T_SPLIT, D_T_SPLIT)
    val PREFS: String get() = decode(S_PREFS, D_PREFS)
    val KEY_CONFIG: String get() = decode(S_KEY_CONFIG, D_KEY_CONFIG)
    val KEY_RUNNING: String get() = decode(S_KEY_RUNNING, D_KEY_RUNNING)
    val HTTPS: String get() = decode(S_HTTPS, D_HTTPS)
    val DEFAULT_USER: String get() = decode(S_DEFAULT_USER, D_DEFAULT_USER)
    val K_ID: String get() = decode(S_K_ID, D_K_ID)
    val K_IP: String get() = decode(S_K_IP, D_K_IP)
    val K_SSH_PORT: String get() = decode(S_K_SSH_PORT, D_K_SSH_PORT)
    val K_SSH_USER: String get() = decode(S_K_SSH_USER, D_K_SSH_USER)
    val K_SSH_PASS: String get() = decode(S_K_SSH_PASS, D_K_SSH_PASS)
    val K_PROXY_HOST: String get() = decode(S_K_PROXY_HOST, D_K_PROXY_HOST)
    val K_PROXY_PORT: String get() = decode(S_K_PROXY_PORT, D_K_PROXY_PORT)
    val K_PROXY_SCHEME: String get() = decode(S_K_PROXY_SCHEME, D_K_PROXY_SCHEME)
    val K_PAYLOAD: String get() = decode(S_K_PAYLOAD, D_K_PAYLOAD)
    val K_SNI: String get() = decode(S_K_SNI, D_K_SNI)
    val K_HOSTKEY: String get() = decode(S_K_HOSTKEY, D_K_HOSTKEY)
}
