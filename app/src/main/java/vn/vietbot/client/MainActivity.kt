      package vn.vietbot.client

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.components.ActivityComponent
import vn.vietbot.client.data.FormRepository
import vn.vietbot.client.data.SettingsRepository
import vn.vietbot.client.ui.ActivationScreen
import vn.vietbot.client.ui.ChatScreen
import vn.vietbot.client.ui.MainScreen
import vn.vietbot.client.ui.ServerFormScreen
import vn.vietbot.client.ui.theme.VoicebotclientandroidTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch


@AndroidEntryPoint
class MainActivity : ComponentActivity() {


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
//        (getSystemService(AUDIO_SERVICE) as AudioManager).mode = AudioManager.MODE_IN_COMMUNICATION
        Log.d("MainActivity", "onCreate")
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                0
            )
        } else {
            Log.d("MainActivity", "Permission granted")
        }
        enableEdgeToEdge()
        setContent {
            VoicebotclientandroidTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val activity = LocalContext.current as Activity
                    val entryPoint = EntryPointAccessors.fromActivity(activity, NavigationEntryPoint::class.java)
                    val settingsRepository = entryPoint.getSettingsRepository()
                    val navigationEvents = entryPoint.getNavigationEvents()

                    MainScreen(
                        settingsRepository = settingsRepository,
                        modifier = Modifier.padding(innerPadding)
                    )

                    LaunchedEffect(navigationEvents) {
                        navigationEvents.collect { route ->
                            Log.d("MainActivity", "Navigation event: $route")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val activity = LocalContext.current as Activity
    val entryPoint = EntryPointAccessors.fromActivity(activity, NavigationEntryPoint::class.java)
    val navigationEvents = entryPoint.getNavigationEvents()


    Log.d("AppNavigation", "navigationEvents: $navigationEvents")

    LaunchedEffect(navController) {
        navigationEvents.collect { route ->
            navController.navigate(route)
        }
    }

    NavHost(navController = navController, startDestination = "form") {
        composable("form") { ServerFormScreen() }
        composable("activation") { ActivationScreen() }
        composable("chat") { ChatScreen() }
    }
}

