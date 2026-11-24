package com.hnwd.bdplayer

internal const val QUARK_H5_LOGIN_HOST = "uop.quark.cn"

internal fun isQuarkH5LoginOrigin(scheme: String?, encodedAuthority: String?): Boolean {
    if (scheme == null || encodedAuthority == null) return false
    // URI scheme 和主机名只接受 ASCII 大小写等价。直接比较原始 authority 保留对 userinfo 和端口的限制。
    return scheme.equals("https", ignoreCase = true) &&
        scheme.all { it.code < 128 } && encodedAuthority.all { it.code < 128 } &&
        (encodedAuthority.equals(QUARK_H5_LOGIN_HOST, ignoreCase = true) ||
            encodedAuthority.equals("$QUARK_H5_LOGIN_HOST:443", ignoreCase = true))
}
