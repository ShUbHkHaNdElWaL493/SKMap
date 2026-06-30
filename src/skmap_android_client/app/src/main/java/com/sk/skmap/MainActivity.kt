package com.sk.skmap

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
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

// ==========================================
// ENUMS & CONSTANTS
// ==========================================

enum class AppState {
    SPLASH, CONNECTING, STREAMING
}

// ==========================================
// MAIN ACTIVITY
// ==========================================

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var sensorManager: SensorManager
    private val udpStreamer = UdpStreamer()

    // Hardware threads
    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null

    // App State
    private var currentAppState by mutableStateOf(AppState.SPLASH)
    private var permissionsGranted by mutableStateOf(false)
    private var serverIp by mutableStateOf("192.168.1.100")
    private var connectionError by mutableStateOf("")
    private var isConnecting by mutableStateOf(false)

    // UI Data State
    private var accelText by mutableStateOf("X: 0.00 | Y: 0.00 | Z: 0.00")
    private var gyroText by mutableStateOf("X: 0.00 | Y: 0.00 | Z: 0.00")
    private var timestampText by mutableStateOf("Waiting for data...")

    // Data Processing State
    private var lastUiUpdateTime = 0L
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val latestAccel = floatArrayOf(0f, 0f, 0f)
    private val latestGyro = floatArrayOf(0f, 0f, 0f)
    private var hasReceivedAccel = false
    private var hasReceivedGyro = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        permissionsGranted = isGranted
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cameraExecutor = Executors.newSingleThreadExecutor()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        val hasCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (hasCamera) {
            permissionsGranted = true
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        // Custom Theme Colors
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
                when (currentAppState) {
                    AppState.SPLASH -> SplashScreen { currentAppState = AppState.CONNECTING }
                    else -> MainScaffold()
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MainScaffold() {
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
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                color = MaterialTheme.colorScheme.background
            ) {
                when (currentAppState) {
                    AppState.CONNECTING -> ConnectScreen(
                        ip = serverIp,
                        errorMessage = connectionError,
                        isConnecting = isConnecting,
                        onIpChange = { serverIp = it; connectionError = "" },
                        onConnectClick = ::handleConnectClick
                    )
                    AppState.STREAMING -> {
                        BackHandler { handleDisconnect() }
                        DashboardScreen(accelText, gyroText, timestampText, cameraExecutor, udpStreamer)
                    }
                    else -> {}
                }
            }
        }
    }

    private fun handleConnectClick() {
        if (!permissionsGranted) {
            connectionError = "Camera permission required."
            return
        }

        isConnecting = true
        connectionError = ""

        CoroutineScope(Dispatchers.Main).launch {
            val success = udpStreamer.connectAndHandshake(serverIp)
            isConnecting = false

            if (success) {
                hasReceivedAccel = false
                hasReceivedGyro = false
                currentAppState = AppState.STREAMING
                startHardwareStreams()

                udpStreamer.startStreaming(
                    onDisconnected = {
                        handleDisconnect()
                        connectionError = "Connection lost."
                    }
                )
            } else {
                connectionError = "Receiver not found."
            }
        }
    }

    private fun handleDisconnect() {
        stopHardwareStreams()
        udpStreamer.disconnect()
        currentAppState = AppState.CONNECTING
    }

    private fun startHardwareStreams() {
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        // Run sensors on a background thread with audio-level priority for low latency
        sensorThread = HandlerThread("ImuThread", Process.THREAD_PRIORITY_URGENT_AUDIO).apply { start() }
        sensorHandler = Handler(sensorThread!!.looper)

        accel?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, sensorHandler) }
        gyro?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, sensorHandler) }
    }

    private fun stopHardwareStreams() {
        sensorManager.unregisterListener(this)
        sensorThread?.quitSafely()
        sensorThread = null
        sensorHandler = null
    }

    @SuppressLint("DefaultLocale")
    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        val currentTime = System.currentTimeMillis()

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                event.values.copyInto(latestAccel)
                hasReceivedAccel = true
            }
            Sensor.TYPE_GYROSCOPE -> {
                event.values.copyInto(latestGyro)
                hasReceivedGyro = true
            }
        }

        // Only stream if we have both data points initialized
        if (currentAppState == AppState.STREAMING && hasReceivedAccel && hasReceivedGyro) {
            val dataString = "${latestAccel[0]},${latestAccel[1]},${latestAccel[2]},${latestGyro[0]},${latestGyro[1]},${latestGyro[2]}"
            udpStreamer.sendImuData(dataString)
        }

        // Throttle UI updates to 10Hz (every 100ms) to prevent UI thread lockup
        if (currentTime - lastUiUpdateTime > 100) {
            timestampText = timeFormat.format(Date(currentTime))
            accelText = "X: ${String.format("%.2f", latestAccel[0])} | Y: ${String.format("%.2f", latestAccel[1])} | Z: ${String.format("%.2f", latestAccel[2])}"
            gyroText = "X: ${String.format("%.2f", latestGyro[0])} | Y: ${String.format("%.2f", latestGyro[1])} | Z: ${String.format("%.2f", latestGyro[2])}"
            lastUiUpdateTime = currentTime
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Required by SensorEventListener interface, but no action needed here.
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        stopHardwareStreams()
        udpStreamer.disconnect()
    }
}

// ==========================================
// NETWORKING CLASS
// ==========================================

class UdpStreamer {
    private var pingSocket: DatagramSocket? = null
    private var imuSocket: DatagramSocket? = null
    private var imgSocket: DatagramSocket? = null
    private var serverAddress: InetAddress? = null

    private val networkScope = CoroutineScope(Dispatchers.IO)
    private var isIntentionallyClosed = false

    private val imuChannel = Channel<String>(Channel.UNLIMITED)
    private val imageChannel = Channel<ByteArray>(Channel.CONFLATED)

    // Hardcoded Destination Ports
    private val portPING = 60000
    private val portIMU = 60001
    private val portIMG = 60002

    /**
     * Initializes sockets and ensures the receiver is active.
     */
    suspend fun connectAndHandshake(ip: String): Boolean = withContext(Dispatchers.IO) {
        try {
            serverAddress = InetAddress.getByName(ip)
            pingSocket = DatagramSocket().apply { soTimeout = 2000 }
            imuSocket = DatagramSocket()
            imgSocket = DatagramSocket()
            isIntentionallyClosed = false

            // Handshake request
            val pingData = "PING".toByteArray()
            val pingPacket = DatagramPacket(pingData, pingData.size, serverAddress, portPING)
            pingSocket?.send(pingPacket)

            // Handshake acknowledgment
            val ackBuffer = ByteArray(256)
            val ackPacket = DatagramPacket(ackBuffer, ackBuffer.size)
            pingSocket?.receive(ackPacket)

            return@withContext String(ackPacket.data, 0, ackPacket.length).trim() == "ACK"
        } catch (e: Exception) {
            Log.e("SKMapNetwork", "Handshake failed: ${e.message}")
            disconnect()
            return@withContext false
        }
    }

    /**
     * Launches coroutines to handle PING, IMU, and Image data on separate UDP lanes.
     */
    fun startStreaming(onDisconnected: () -> Unit) {
        networkScope.coroutineContext.cancelChildren()
        pingSocket?.soTimeout = 3000

        // Ping Lane - Keeps connection alive and verifies receiver presence
        networkScope.launch {
            try {
                while (isActive && !isIntentionallyClosed) {
                    delay(2000.milliseconds)
                    if (isIntentionallyClosed || pingSocket?.isClosed == true) break

                    val pingData = "PING".toByteArray()
                    pingSocket?.send(DatagramPacket(pingData, pingData.size, serverAddress, portPING))

                    val ackPacket = DatagramPacket(ByteArray(256), 256)
                    pingSocket?.receive(ackPacket)

                    if (String(ackPacket.data, 0, ackPacket.length).trim() != "ACK") {
                        throw Exception("Invalid ping response")
                    }
                }
            } catch (e: Exception) {
                if (!isIntentionallyClosed) {
                    Log.e("SKMapNetwork", "Connection lost: ${e.message}")
                    withContext(Dispatchers.Main) { onDisconnected() }
                }
            }
        }

        // IMU Lane
        networkScope.launch {
            for (data in imuChannel) {
                if (imuSocket?.isClosed == true || isIntentionallyClosed) break
                try {
                    val payload = "IMU|${System.currentTimeMillis()}|$data".toByteArray()
                    imuSocket?.send(DatagramPacket(payload, payload.size, serverAddress, portIMU))
                } catch (_: Exception) { }
            }
        }

        // Image Lane
        networkScope.launch {
            for (jpegBytes in imageChannel) {
                if (imgSocket?.isClosed == true || isIntentionallyClosed) break
                try {
                    val header = "IMG|${System.currentTimeMillis()}|".toByteArray()
                    val payload = header + jpegBytes
                    imgSocket?.send(DatagramPacket(payload, payload.size, serverAddress, portIMG))
                } catch (_: Exception) { }
            }
        }
    }

    fun sendImuData(dataString: String) = imuChannel.trySend(dataString)
    fun sendImageData(jpegBytes: ByteArray) = imageChannel.trySend(jpegBytes)

    fun disconnect() {
        isIntentionallyClosed = true
        networkScope.coroutineContext.cancelChildren()

        pingSocket?.close()
        imuSocket?.close()
        imgSocket?.close()

        pingSocket = null
        imuSocket = null
        imgSocket = null
    }
}

// ==========================================
// COMPOSE SCREENS
// ==========================================

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
        Text("SKMap", color = Color(0xFFC62A2A), fontSize = 56.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
    }
}

@Composable
fun ConnectScreen(
    ip: String,
    errorMessage: String,
    isConnecting: Boolean,
    onIpChange: (String) -> Unit,
    onConnectClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Configure Connection", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = ip,
            onValueChange = onIpChange,
            label = { Text("SKMap Desktop Client IP Address") },
            modifier = Modifier.fillMaxWidth()
        )

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
            DataSectionBlock("Accelerometer (m/s²)", accel)
            DataSectionBlock("Gyroscope (rad/s)", gyro)
            HorizontalDivider()
            DataSectionBlock("Timestamp", timestamp)
        }
    }
}

@Composable
fun DataSectionBlock(title: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(title, fontSize = 14.sp, color = Color.Gray, fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.height(4.dp))
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
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
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                // Pre-allocate buffers outside the analysis loop to prevent GC churn
                var nv21Buffer: ByteArray? = null
                val outStream = ByteArrayOutputStream(640 * 480 * 2)

                imageAnalysis.setAnalyzer(executor) { imageProxy ->
                    if (imageProxy.format == ImageFormat.YUV_420_888) {
                        val yBuffer = imageProxy.planes[0].buffer
                        val uBuffer = imageProxy.planes[1].buffer
                        val vBuffer = imageProxy.planes[2].buffer

                        val ySize = yBuffer.remaining()
                        val uSize = uBuffer.remaining()
                        val vSize = vBuffer.remaining()
                        val totalSize = ySize + uSize + vSize

                        if (nv21Buffer == null || nv21Buffer!!.size != totalSize) {
                            nv21Buffer = ByteArray(totalSize)
                        }

                        yBuffer.get(nv21Buffer, 0, ySize)
                        vBuffer.get(nv21Buffer, ySize, vSize)
                        uBuffer.get(nv21Buffer, ySize + vSize, uSize)

                        outStream.reset()
                        val yuvImage = YuvImage(nv21Buffer, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
                        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 70, outStream)

                        streamer.sendImageData(outStream.toByteArray())
                    }
                    imageProxy.close()
                }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
                } catch (exc: Exception) {
                    Log.e("SKMapHardware", "Camera binding failed", exc)
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}