package com.mmwtl.atlascodecfix

import android.app.Application

class CodecFixApp : Application() {
    lateinit var prefs: CodecFixPrefs
        private set
    lateinit var adbClient: AdbClient
        private set
    lateinit var codecFixRepository: HevcCodecFixRepository
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = CodecFixPrefs(this)
        adbClient = AdbClient(prefs)
        codecFixRepository = HevcCodecFixRepository(this, adbClient)
    }
}
