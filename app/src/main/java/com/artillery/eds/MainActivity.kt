package com.artillery.eds

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.autel.drone.sdk.libbase.error.IAutelCode
import com.autel.drone.sdk.vmodelx.SDKManager
import com.autel.drone.sdk.vmodelx.interfaces.IAutelDroneDevice
import com.autel.drone.sdk.vmodelx.interfaces.IKeyManager
import com.autel.drone.sdk.vmodelx.manager.DeviceManager
import com.autel.drone.sdk.vmodelx.manager.keyvalue.callback.CommonCallbacks
import com.autel.drone.sdk.vmodelx.manager.keyvalue.key.FlightControlKey
import com.autel.drone.sdk.vmodelx.manager.keyvalue.key.base.KeyTools
import com.autel.player.player.AutelPlayerManager
import com.autel.player.player.AutelPlayerView
import org.locationtech.proj4j.CRSFactory
import org.locationtech.proj4j.CoordinateTransformFactory
import org.locationtech.proj4j.ProjCoordinate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

class MainActivity : AppCompatActivity() {

    private var autelPlayerView: AutelPlayerView? = null
    private var tvZoneInfo: TextView? = null
    private var tvLiveAltitude: TextView? = null
    private var tvDistanceInfo: TextView? = null
    private var tvDroneBattery: TextView? = null
    private var tvRcBattery: TextView? = null
    private var tvSavedEasting: TextView? = null
    private var tvSavedNorthing: TextView? = null
    private var tvSavedHeight: TextView? = null
    private var tvFos1: TextView? = null
    private var tvFos2: TextView? = null
    private var tvLiveDroneGrid: TextView? = null
    private var btnVoice: Button? = null

    private var lockedEasting: Double = 0.0
    private var lockedNorthing: Double = 0.0
    private var isTargetLocked = false
    private var shotCount = 0

    // ड्रोन टेलीमेट्री वेरिएबल्स
    private var droneLat: Double = 0.0
    private var droneLon: Double = 0.0
    private var droneRelAlt: Double = 0.0
    private var takeoffGroundAmsl: Double = 0.0
    private var droneYaw: Double = 0.0
    private var gimbalPitch: Double = -90.0

    private var isDroneConnected = false
    private var connectedDrone: IAutelDroneDevice? = null

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isVoiceListening = true

    private val telemetryHandler = Handler(Looper.getMainLooper())
    private var isMonitoring = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)
            initViews()
            checkPermissions()
            setupButtons()
            initVoiceSystem()
            initAutelSDK()
        } catch (e: Exception) {
            Toast.makeText(this, "Init Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun initViews() {
        autelPlayerView = findViewById(R.id.autel_player_view)
        tvZoneInfo = findViewById(R.id.tv_zone_info)
        tvLiveAltitude = findViewById(R.id.tv_live_altitude)
        tvDistanceInfo = findViewById(R.id.tv_distance_info)
        tvDroneBattery = findViewById(R.id.tv_drone_battery)
        tvRcBattery = findViewById(R.id.tv_rc_battery)
        tvSavedEasting = findViewById(R.id.tv_saved_easting)
        tvSavedNorthing = findViewById(R.id.tv_saved_northing)
        tvSavedHeight = findViewById(R.id.tv_saved_height)
        tvFos1 = findViewById(R.id.tv_fos_1)
        tvFos2 = findViewById(R.id.tv_fos_2)
        tvLiveDroneGrid = findViewById(R.id.tv_live_drone_grid)
        btnVoice = findViewById(R.id.btn_voice)
    }

    private fun initVoiceSystem() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
            }
        }

        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onError(error: Int) {
                    if (isVoiceListening) restartListeningWithDelay()
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val spokenText = matches?.firstOrNull()?.lowercase(Locale.ROOT) ?: ""

                    when {
                        spokenText.contains("lock") || spokenText.contains("target") -> performLockTarget()
                        spokenText.contains("record") || spokenText.contains("shot") || spokenText.contains("fire") -> performRecordShot()
                        spokenText.contains("return") || spokenText.contains("home") || spokenText.contains("rth") -> performRthCommand()
                        spokenText.contains("height") || spokenText.contains("altitude") -> speak("Drone height is ${droneRelAlt.roundToInt()} meters")
                        spokenText.contains("direction") || spokenText.contains("heading") -> {
                            val deg = droneYaw.roundToInt()
                            speak("Drone heading $deg degrees ${getCompassDirection(droneYaw)}")
                        }
                    }
                    if (isVoiceListening) restartListeningWithDelay()
                }
            })
            startListening()
        }
    }

    private fun startListening() {
        if (!isVoiceListening) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US)
        }
        try {
            speechRecognizer?.startListening(intent)
        } catch (_: Exception) {}
    }

    private fun restartListeningWithDelay() {
        Handler(Looper.getMainLooper()).postDelayed({
            if (isVoiceListening) startListening()
        }, 800)
    }

    private fun speak(message: String) {
        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun initAutelSDK() {
        try {
            SDKManager.get().init(applicationContext, true)
            autelPlayerView?.let { playerView ->
                AutelPlayerManager.getInstance().init(this, false)
                AutelPlayerManager.getInstance().addAutelPlayer(playerView)
            }
        } catch (_: Exception) {}

        startDeviceAndTelemetryLoop()
    }

    private fun startDeviceAndTelemetryLoop() {
        isMonitoring = true
        val runnable = object : Runnable {
            override fun run() {
                if (!isMonitoring) return
                try {
                    val drone = DeviceManager.getDeviceManager().getFirstDroneDevice()
                    if (drone != null && drone.isConnected()) {
                        if (!isDroneConnected) {
                            isDroneConnected = true
                            connectedDrone = drone
                            speak("Drone connected")
                        }
                        readDroneStateSafely(drone)
                    } else {
                        isDroneConnected = false
                        connectedDrone = null
                        tvZoneInfo?.text = "OT:360° | OFF | HDG:---°"
                        tvLiveAltitude?.text = "ALT: 0m"
                        tvLiveDroneGrid?.text = "TGT: Waiting for Drone..."
                    }
                } catch (_: Exception) {}
                telemetryHandler.postDelayed(this, 500)
            }
        }
        telemetryHandler.post(runnable)
    }

    private fun readNumberProperty(target: Any?, methodName: String): Double? {
        if (target == null) return null
        return try {
            val method = target.javaClass.methods.firstOrNull { it.name == methodName && it.parameterCount == 0 }
            val value = method?.invoke(target)
            (value as? Number)?.toDouble()
        } catch (_: Exception) {
            null
        }
    }

    private fun readObjectProperty(target: Any?, methodName: String): Any? {
        if (target == null) return null
        return try {
            val method = target.javaClass.methods.firstOrNull { it.name == methodName && it.parameterCount == 0 }
            method?.invoke(target)
        } catch (_: Exception) {
            null
        }
    }

    private fun readDroneStateSafely(drone: IAutelDroneDevice) {
        try {
            val stateData = drone.getDeviceStateData()
            val flightData = stateData?.flightControlData

            if (flightData != null) {
                droneLat = flightData.droneLatitude
                droneLon = flightData.droneLongitude
                droneRelAlt = flightData.altitude.toDouble()

                // Yaw (दिशा) को सुरक्षित तरीके से पढ़ें
