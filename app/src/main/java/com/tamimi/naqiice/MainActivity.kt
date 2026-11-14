package com.tamimi.naqiice

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tamimi.naqiice.data.Me
import com.tamimi.naqiice.data.Repo
import com.tamimi.naqiice.tracking.TrackingService
import com.tamimi.naqiice.ui.AppShell
import com.tamimi.naqiice.ui.Ice
import com.tamimi.naqiice.ui.LocalNav
import com.tamimi.naqiice.ui.LocalRepo
import com.tamimi.naqiice.ui.LoginScreen
import com.tamimi.naqiice.ui.Nav
import com.tamimi.naqiice.ui.PureIceTheme
import com.tamimi.naqiice.ui.Screen

class PureIceApp : Application() {
    lateinit var repo: Repo
        private set

    override fun onCreate() {
        super.onCreate()
        repo = Repo(this)
        repo.trackingController = { TrackingService.update(this, it) }
        repo.start()
    }
}

fun homeFor(me: Me?): Screen = when (me?.primaryRole) {
    "admin" -> Screen.Admin
    "supervisor" -> Screen.Trips
    "distributor" -> Screen.Today
    else -> Screen.Pending
}

class MainActivity : ComponentActivity() {
    private val repo get() = (application as PureIceApp).repo

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PureIceTheme {
                CompositionLocalProvider(LocalRepo provides repo) {
                    val loading by repo.loading.collectAsState()
                    val me by repo.me.collectAsState()
                    val user = me
                    when {
                        loading -> Splash()
                        user == null -> LoginScreen()
                        // A different account or role starts from its own home screen.
                        else -> key(user.id, user.primaryRole) {
                            val nav = remember { Nav(homeFor(user)) }
                            BackHandler(enabled = nav.stack.size > 1) { nav.back() }
                            CompositionLocalProvider(LocalNav provides nav) { AppShell(user) }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The tracking service may only be started while the app is visible.
        TrackingService.update(this, repo)
    }
}

@androidx.compose.runtime.Composable
private fun Splash() {
    Box(Modifier.fillMaxSize().background(Ice.gradient), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Image(painterResource(R.drawable.logo), "ثلج نقي", Modifier.size(96.dp))
            Text("ثلج نقي", color = Ice.onPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
    }
}
