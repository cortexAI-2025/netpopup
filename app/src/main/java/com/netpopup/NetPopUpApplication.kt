package com.netpopup

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point — Hilt initializes the DI graph here.
 * Firebase is auto-initialized via google-services.json at build time.
 */
@HiltAndroidApp
class NetPopUpApplication : Application()
