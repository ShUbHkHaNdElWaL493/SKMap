package com.sk.skmap

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.milliseconds

// Define the three phases of the app lifecycle
enum class AppState {
    SPLASH, CONNECTING, STREAMING
}

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var sensorManager: SensorManager

    private var currentAppState by mutableStateOf(AppState.SPLASH)
    private var permissionsGranted by mutableStateOf(false)

    private var serverIp by mutableStateOf("192.168.1.100")
    private var serverPort by mutableStateOf("9999")

    private var accelText by mutableStateOf("X: 0.00 | Y: 0.00 | Z: 0.00")
    private var gyroText by mutableStateOf("X: 0.00 | Y: 0.00 | Z: 0.00")
    private var timestampText by mutableStateOf("Waiting for data...")

    private var lastUiUpdateTime = 0L
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    // Simplified to only request Camera permission
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            permissionsGranted = true
            startHardwareStreams()
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        val hasCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

        val redColorScheme = darkColorScheme(
            primary = Color(0xFFD32F2F),
            onPrimary = Color.White,
            background = Color(0xFF121212),
            onBackground = Color.White,
            surface = Color(0xFF1E1E1E),
            onSurface = Color.White
        )

        setContent {
            MaterialTheme(colorScheme = redColorScheme) {
                if (currentAppState == AppState.SPLASH) {
                    SplashScreen { currentAppState = AppState.CONNECTING }
                } else {
                    Scaffold(
                        topBar = {
                            TopAppBar(
                                title = { Text("SKMap", fontWeight = FontWeight.Bold) },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                modifier = Modifier.shadow(8.dp)
                            )
                        }
                    ) { innerPadding ->
                        Surface(
                            modifier = Modifier.fillMaxSize().padding(innerPadding),
                            color = MaterialTheme.colorScheme.background
                        ) {
                            when (currentAppState) {
                                AppState.CONNECTING -> ConnectScreen(
                                    ip = serverIp,
                                    port = serverPort,
                                    onIpChange = { serverIp = it },
                                    onPortChange = { serverPort = it },
                                    onConnectClick = {
                                        if (permissionsGranted) {
                                            currentAppState = AppState.STREAMING
                                        }
                                    }
                                )
                                AppState.STREAMING -> {
                                    // Intercept the back button to return to connection screen
                                    BackHandler {
                                        currentAppState = AppState.CONNECTING
                                    }

                                    if (permissionsGranted) {
                                        DashboardScreen(accelText, gyroText, timestampText, cameraExecutor)
                                    } else {
                                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                            Text("Waiting for camera permission...")
                                        }
                                    }
                                }
                                else -> {}
                            }
                        }
                    }
                }
            }
        }

        if (hasCamera) {
            permissionsGranted = true
            startHardwareStreams()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startHardwareStreams() {
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        accel?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
        gyro?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            val currentTime = System.currentTimeMillis()
            // Throttle UI updates to prevent freezing, but keep grabbing data at max speed in background
            if (currentTime - lastUiUpdateTime > 100) {
                timestampText = timeFormat.format(Date(currentTime))

                when (it.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        accelText = "X: ${String.format("%.2f", it.values[0])} | Y: ${String.format("%.2f", it.values[1])} | Z: ${String.format("%.2f", it.values[2])}"
                    }
                    Sensor.TYPE_GYROSCOPE -> {
                        gyroText = "X: ${String.format("%.2f", it.values[0])} | Y: ${String.format("%.2f", it.values[1])} | Z: ${String.format("%.2f", it.values[2])}"
                        lastUiUpdateTime = currentTime
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        sensorManager.unregisterListener(this)
    }
}

// --- COMPOSE SCREENS ---

@Composable
fun SplashScreen(onTimeout: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(3500.milliseconds)
        onTimeout()
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize().background(Color(0xFF1E1E1E))
    ) {
        Text("SKMap", color = Color.White, fontSize = 56.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
    }
}

@Composable
fun ConnectScreen(ip: String, port: String, onIpChange: (String) -> Unit, onPortChange: (String) -> Unit, onConnectClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Configure Connection", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(32.dp))
        OutlinedTextField(value = ip, onValueChange = onIpChange, label = { Text("SKMap Desktop Client IP Address") }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(value = port, onValueChange = onPortChange, label = { Text("SKMap Desktop Client Port") }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onConnectClick,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text("Connect", fontSize = 18.sp)
        }
    }
}

@Composable
fun DashboardScreen(accel: String, gyro: String, timestamp: String, executor: ExecutorService) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        // Top Section: Camera Feed
        Column(modifier = Modifier.weight(1.2f).fillMaxWidth()) {
            Text("Camera Feed", fontSize = 14.sp, color = Color.Gray, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                    CameraFeed(executor)
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        // Bottom Section: Sensor Data grouped logically
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            // Grouped IMU Readings
            DataSectionBlock(title = "Accelerometer (m/s²)") {
                Text(accel, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            }
            DataSectionBlock(title = "Gyroscope (rad/s)") {
                Text(gyro, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            }

            HorizontalDivider()

            // Dedicated Timestamp Section
            DataSectionBlock(title = "Timestamp") {
                Text(timestamp, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            }
        }
    }
}

// A flexible container that allows us to pass multiple Text elements into one section
@Composable
fun DataSectionBlock(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(title, fontSize = 14.sp, color = Color.Gray, fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.height(8.dp))
        content()
    }
}

@Composable
fun CameraFeed(executor: ExecutorService) {
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(executor) { imageProxy ->
                    imageProxy.close()
                }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
                } catch (exc: Exception) { Log.e("SlamHardware", "Camera binding failed", exc) }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}