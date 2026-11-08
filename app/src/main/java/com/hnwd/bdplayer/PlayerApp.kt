package com.hnwd.bdplayer

import android.app.Application

class PlayerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // PDF 渲染服务是 isolatedProcess，不允许访问 SharedPreferences
        if (android.os.Process.myUid() == applicationInfo.uid) ThemeStore.apply(this)
    }
}
