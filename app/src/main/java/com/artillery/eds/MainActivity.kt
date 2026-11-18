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
import com.autel.drone.sdk.v2.bean.DronePosition3D
import com.autel.drone.sdk.v2.interfaces.IBaseDevice
import com.autel.drone.sdk.v2.interfaces.IDeviceListener
import com.autel.drone.sdk.v2.interfaces.IKeyManager
import com.autel.drone.sdk.v2.sdk.SDKManager
import com.autel.drone.sdk.v2.sdk.key.BatteryKey
import com.autel.drone.sdk.v2.sdk.key.CommonCallbacks
import com.autel.drone.sdk.v2.sdk.key.FlightControlKey
import com.autel.drone.sdk.v2.sdk.key.GimbalKey
import com.autel.drone.sdk.v2.sdk.key.KeyTools
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

    // OT हमेशा 360° फिक्स रहेगी (गन को पास करने के लिए)
    private val fixedOtBearingDeg: Double = 360.0

    // ड्रोन टेलीमेट्री
    private var droneLat: Double = 0.0
    private var droneLon: Double = 0.0
    private var droneRelAlt: Double = 0.0
    private var takeoffGroundAmsl: Double = 0.0
    private var droneYaw: Double = 0.0
    private var gimbalPitch: Double = -90.0

    private var isDroneConnected = false
    private var keyManager: IKeyManager? = null

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isVoiceListening = true

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
        SDKManager.get().init(applicationContext, true)

        autelPlayerView?.let { playerView ->
            AutelPlayerManager.getInstance().init(this, false)
            AutelPlayerManager.getInstance().addAutelPlayer(playerView)
        }

        SDKManager.get().addDeviceListener(object : IDeviceListener {
            override fun onDeviceChanged(devices: List<IBaseDevice>) {
                val drone = devices.firstOrNull()
                if (drone != null && drone.isConnected()) {
                    isDroneConnected = true
                    keyManager = drone.getKeyManager()
                    runOnUiThread {
                        updateTopStatusBar()
                        speak("Drone connected")
                    }
                    startDroneTelemetry()
                } else {
                    isDroneConnected = false
                    runOnUiThread {
                        tvZoneInfo?.text = "OT:360° | OFF | HDG:---°"
                        tvLiveAltitude?.text = "ALT: 0m"
                        tvLiveDroneGrid?.text = "TGT: Waiting for Drone..."
                    }
                }
            }
        })
    }

    private fun startDroneTelemetry() {
        val km = keyManager ?: return

        val locationKey = KeyTools.createKey(FlightControlKey.KeyDroneLocation)
        km.listen(locationKey, object : CommonCallbacks.KeyListener<DronePosition3D> {
            override fun onValueChange(oldValue: DronePosition3D?, newValue: DronePosition3D?) {
                newValue?.let { pos ->
                    droneLat = pos.latitude
                    droneLon = pos.longitude
                    droneRelAlt = pos.altitude
                    updateLiveTargetDisplay()
                }
            }
        })

        val homeLocKey = KeyTools.createKey(FlightControlKey.KeyHomeLocation)
        km.listen(homeLocKey, object : CommonCallbacks.KeyListener<DronePosition3D> {
            override fun onValueChange(oldValue: DronePosition3D?, newValue: DronePosition3D?) {
                newValue?.let { homePos ->
                    if (homePos.altitude != 0.0) {
                        takeoffGroundAmsl = homePos.altitude
                    }
                }
            }
        })

        val yawKey = KeyTools.createKey(FlightControlKey.KeyDroneYaw)
        km.listen(yawKey, object : CommonCallbacks.KeyListener<Double> {
            override fun onValueChange(oldValue: Double?, newValue: Double?) {
                newValue?.let {
                    droneYaw = (it + 360.0) % 360.0
                    updateLiveTargetDisplay()
                }
            }
        })

        val pitchKey = KeyTools.createKey(GimbalKey.KeyGimbalPitch)
        km.listen(pitchKey, object : CommonCallbacks.KeyListener<Float> {
