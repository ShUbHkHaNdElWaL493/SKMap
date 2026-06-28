package com.sk.skmap

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.milliseconds

enum class AppState {
    SPLASH, CONNECTING, STREAMING
}

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var sensorManager: SensorManager
    private val udpStreamer = UdpStreamer()

    private var currentAppState by mutableStateOf(AppState.SPLASH)
    private var permissionsGranted by mutableStateOf(false)

    private var serverIp by mutableStateOf("192.168.1.100")
    private var serverPort by mutableStateOf("9999")
    private var connectionError by mutableStateOf("")
    private var isConnecting by mutableStateOf(false)

    private var accelText by mutableStateOf("X: 0.00 | Y: 0.00 | Z: 0.00")
    private var gyroText by mutableStateOf("X: 0.00 | Y: 0.00 | Z: 0.00")
    private var timestampText by mutableStateOf("Waiting for data...")

    private var lastUiUpdateTime = 0L
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private var latestAccel = floatArrayOf(0f, 0f, 0f)
    private var latestGyro = floatArrayOf(0f, 0f, 0f)
    private var hasReceivedAccel = false
    private var hasReceivedGyro = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            permissionsGranted = true
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
                                    errorMessage = connectionError,
                                    isConnecting = isConnecting,
                                    onIpChange = { serverIp = it; connectionError = "" },
                                    onPortChange = { serverPort = it; connectionError = "" },
                                    onConnectClick = {
                                        if (permissionsGranted) {
                                            initiateConnection()
                                        } else {
                                            connectionError = "Camera permission required."
                                        }
                                    }
                                )
                                AppState.STREAMING -> {
                                    // Manual Disconnect via Back Button
                                    BackHandler {
                                        stopHardwareStreams()
                                        udpStreamer.disconnect()
                                        currentAppState = AppState.CONNECTING
                                    }
                                    DashboardScreen(accelText, gyroText, timestampText, cameraExecutor, udpStreamer)
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
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun initiateConnection() {
        isConnecting = true
        connectionError = ""

        CoroutineScope(Dispatchers.Main).launch {
            val success = udpStreamer.connectAndHandshake(serverIp, serverPort.toInt())
            isConnecting = false

            if (success) {
                hasReceivedAccel = false
                hasReceivedGyro = false
                currentAppState = AppState.STREAMING
                startHardwareStreams()

                // Start monitoring the connection for unexpected drops
                udpStreamer.startHeartbeat(
                    onDisconnected = {
                        stopHardwareStreams()
                        udpStreamer.disconnect()
                        connectionError = "Connection lost."
                        currentAppState = AppState.CONNECTING
                    }
                )
            } else {
                connectionError = "Receiver not found."
            }
        }
    }

    private fun startHardwareStreams() {
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        accel?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
        gyro?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
    }

    private fun stopHardwareStreams() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            val currentTime = System.currentTimeMillis()

            when (it.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    it.values.copyInto(latestAccel)
                    hasReceivedAccel = true
                }
                Sensor.TYPE_GYROSCOPE -> {
                    it.values.copyInto(latestGyro)
                    hasReceivedGyro = true
                }
            }

            if (currentAppState == AppState.STREAMING && hasReceivedAccel && hasReceivedGyro) {
                val dataString = "${currentTime},${latestAccel[0]},${latestAccel[1]},${latestAccel[2]},${latestGyro[0]},${latestGyro[1]},${latestGyro[2]}"
                udpStreamer.sendImuData(dataString)
            }

            if (currentTime - lastUiUpdateTime > 100) {
                timestampText = timeFormat.format(Date(currentTime))
                accelText = "X: ${String.format("%.2f", latestAccel[0])} | Y: ${String.format("%.2f", latestAccel[1])} | Z: ${String.format("%.2f", latestAccel[2])}"
                gyroText = "X: ${String.format("%.2f", latestGyro[0])} | Y: ${String.format("%.2f", latestGyro[1])} | Z: ${String.format("%.2f", latestGyro[2])}"
                lastUiUpdateTime = currentTime
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        stopHardwareStreams()
        udpStreamer.disconnect()
    }
}

// --- NETWORKING CLASS ---

class UdpStreamer {
    private var socket: DatagramSocket? = null
    private var serverAddress: InetAddress? = null
    private var port: Int = 0

    private val ioScope = CoroutineScope(Dispatchers.IO)
    private var heartbeatJob: Job? = null
    private var isIntentionallyClosed = false

    suspend fun connectAndHandshake(ip: String, targetPort: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            port = targetPort
            serverAddress = InetAddress.getByName(ip)
            socket = DatagramSocket()
            isIntentionallyClosed = false

            socket?.soTimeout = 2000

            val pingData = "PING".toByteArray()
            val pingPacket = DatagramPacket(pingData, pingData.size, serverAddress, port)
            socket?.send(pingPacket)

            val ackBuffer = ByteArray(256)
            val ackPacket = DatagramPacket(ackBuffer, ackBuffer.size)
            socket?.receive(ackPacket)

            val response = String(ackPacket.data, 0, ackPacket.length).trim()
            return@withContext response == "ACK"

        } catch (e: Exception) {
            Log.e("SKMapNetwork", "Handshake failed: ${e.message}")
            socket?.close()
            socket = null
            return@withContext false
        }
    }

    fun startHeartbeat(onDisconnected: () -> Unit) {
        heartbeatJob = ioScope.launch {
            try {
                // If we don't get an ACK within 3 seconds, assume connection is dead
                socket?.soTimeout = 3000

                while (isActive && !isIntentionallyClosed) {
                    delay(2000.milliseconds) // Wait 2 seconds between pings

                    if (isIntentionallyClosed || socket == null || socket?.isClosed == true) break

                    val pingData = "PING".toByteArray()
                    val pingPacket = DatagramPacket(pingData, pingData.size, serverAddress, port)
                    socket?.send(pingPacket)

                    val ackBuffer = ByteArray(256)
                    val ackPacket = DatagramPacket(ackBuffer, ackBuffer.size)

                    // This will block until an ACK is received OR 3 seconds pass (triggering an exception)
                    socket?.receive(ackPacket)

                    val response = String(ackPacket.data, 0, ackPacket.length).trim()
                    if (response != "ACK") {
                        throw Exception("Invalid response received")
                    }
                }
            } catch (e: Exception) {
                // Ignore the error if the user pressed the back button to disconnect manually
                if (!isIntentionallyClosed) {
                    Log.e("SKMapNetwork", "Connection lost: ${e.message}")
                    withContext(Dispatchers.Main) {
                        onDisconnected()
                    }
                }
            }
        }
    }

    fun sendImuData(dataString: String) {
        if (socket == null || socket?.isClosed == true) return
        ioScope.launch {
            try {
                val payload = "IMU|$dataString".toByteArray()
                val packet = DatagramPacket(payload, payload.size, serverAddress, port)
                socket?.send(packet)
            } catch (_: Exception) {
                // Log silently on UDP drops
            }
        }
    }

    fun sendImageData(jpegBytes: ByteArray) {
        if (socket == null || socket?.isClosed == true) return
        ioScope.launch {
            try {
                val prefix = "IMG|".toByteArray()
                val payload = prefix + jpegBytes
                val packet = DatagramPacket(payload, payload.size, serverAddress, port)
                socket?.send(packet)
            } catch (e: Exception) {
                Log.e("SKMapNetwork", "Failed to send Image", e)
            }
        }
    }

    fun disconnect() {
        isIntentionallyClosed = true
        heartbeatJob?.cancel()
        socket?.close()
        socket = null
    }
}

// --- COMPOSE SCREENS ---

@Composable
fun SplashScreen(onTimeout: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(3500.milliseconds)
        onTimeout()
    }
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().background(Color(0xFF1E1E1E))) {
        Text("SKMap", color = Color(0xFFC62A2A), fontSize = 56.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
    }
}

@Composable
fun ConnectScreen(
    ip: String, port: String, errorMessage: String, isConnecting: Boolean,
    onIpChange: (String) -> Unit, onPortChange: (String) -> Unit, onConnectClick: () -> Unit
) {
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

        if (errorMessage.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(errorMessage, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
        }

        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onConnectClick,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            enabled = !isConnecting
        ) {
            if (isConnecting) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
            } else {
                Text("Connect", fontSize = 18.sp)
            }
        }
    }
}

@Composable
fun DashboardScreen(accel: String, gyro: String, timestamp: String, executor: ExecutorService, streamer: UdpStreamer) {
    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Column(modifier = Modifier.weight(1.2f).fillMaxWidth()) {
            Text("Camera Feed", fontSize = 14.sp, color = Color.Gray, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                    CameraFeed(executor, streamer)
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        Column(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.SpaceEvenly) {
            DataSectionBlock(title = "Accelerometer (m/s²)") {
                Text(accel, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            }
            DataSectionBlock(title = "Gyroscope (rad/s)") {
                Text(gyro, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            }
            HorizontalDivider()
            DataSectionBlock(title = "Timestamp") {
                Text(timestamp, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            }
        }
    }
}

@Composable
fun DataSectionBlock(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(title, fontSize = 14.sp, color = Color.Gray, fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.height(8.dp))
        content()
    }
}

@Composable
fun CameraFeed(executor: ExecutorService, streamer: UdpStreamer) {
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
                    if (imageProxy.format == ImageFormat.YUV_420_888) {
                        val yBuffer = imageProxy.planes[0].buffer
                        val uBuffer = imageProxy.planes[1].buffer
                        val vBuffer = imageProxy.planes[2].buffer
                        val ySize = yBuffer.remaining()
                        val uSize = uBuffer.remaining()
                        val vSize = vBuffer.remaining()
                        val nv21 = ByteArray(ySize + uSize + vSize)
                        yBuffer.get(nv21, 0, ySize)
                        vBuffer.get(nv21, ySize, vSize)
                        uBuffer.get(nv21, ySize + vSize, uSize)

                        val out = ByteArrayOutputStream()
                        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
                        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 70, out)

                        streamer.sendImageData(out.toByteArray())
                    }
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