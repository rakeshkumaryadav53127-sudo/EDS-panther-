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
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.autel.drone.sdk.libbase.error.IAutelCode
import com.autel.drone.sdk.vmodelx.SDKManager
import com.autel.drone.sdk.vmodelx.interfaces.IAutelDroneDevice
import com.autel.drone.sdk.vmodelx.manager.DeviceManager
import com.autel.drone.sdk.vmodelx.manager.keyvalue.callback.CommonCallbacks
import com.autel.drone.sdk.vmodelx.manager.keyvalue.key.FlightControlKey
import com.autel.drone.sdk.vmodelx.manager.keyvalue.key.base.KeyTools
import com.autel.player.player.AutelPlayerManager
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

    private var autelPlayerView: View? = null
    private var tvZoneInfo: TextView? = null
    private var tvDistanceInfo: TextView? = null
    private var tvDroneBattery: TextView? = null
    private var tvSavedEasting: TextView? = null
    private var tvSavedNorthing: TextView? = null
    private var tvSavedHeight: TextView? = null
    private var tvFos1: TextView? = null
    private var tvFos2: TextView? = null
    private var tvFfeStatus: TextView? = null
    private var tvLiveDroneGrid: TextView? = null
    private var btnVoice: Button? = null

    private var lockedEasting = 0.0
    private var lockedNorthing = 0.0
    private var isTargetLocked = false
    private var shotCount = 0

    private var droneLat = 0.0
    private var droneLon = 0.0
    private var droneRelAlt = 0.0
    private var takeoffGroundAmsl = 0.0
    private var droneYaw = 0.0
    private var gimbalPitch = -90.0

    private var isDroneConnected = false
    private var connectedDrone: IAutelDroneDevice? = null    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isVoiceListening = true
    private val handler = Handler(Looper.getMainLooper())

        override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)

            autelPlayerView = findViewById(R.id.autel_player_view)
            tvZoneInfo = findViewById(R.id.tv_zone_info)
            tvDistanceInfo = findViewById(R.id.tv_distance_info)
            tvDroneBattery = findViewById(R.id.tv_drone_battery)
            tvSavedEasting = findViewById(R.id.tv_saved_easting)
            tvSavedNorthing = findViewById(R.id.tv_saved_northing)
            tvSavedHeight = findViewById(R.id.tv_saved_height)
            tvFos1 = findViewById(R.id.tv_fos_1)
            tvFos2 = findViewById(R.id.tv_fos_2)
            tvFfeStatus = findViewById(R.id.tv_ffe_status)
            tvLiveDroneGrid = findViewById(R.id.tv_live_drone_grid)
            btnVoice = findViewById(R.id.btn_voice)

            checkPermissions()
            setupButtons()
            try { initVoice() } catch (_: Exception) {}
            try { initDrone() } catch (e: Throwable) {
                tvZoneInfo?.text = "SDK ERR: ${e.message}"
            }
        } catch (e: Throwable) {
            val tv = TextView(this).apply {
                text = "CRASH REASON:\n${e.stackTraceToString()}"
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xFF000000.toInt())
                setPadding(30, 30, 30, 30)
            }
            setContentView(tv)
        }
    }


    private fun getProp(obj: Any?, name: String): Any? {
        if (obj == null) return null
        return try {
            val m = obj.javaClass.getMethod(name)
            m.invoke(obj)
        } catch (_: Exception) { null }
    }

    private fun startVideoFeed() {
        val container = autelPlayerView as? ViewGroup ?: return
        try {
            val pm = AutelPlayerManager.getInstance()
            pm.javaClass.methods.firstOrNull { it.name == "init" && it.parameterTypes.size == 2 }
                ?.invoke(pm, this, false)

            val viewClassNames = listOf(
                "com.autel.player.player.AutelPlayerView",
                "com.autel.player.widget.AutelPlayerView",
                "com.autel.player.view.AutelPlayerView",
                "com.autel.drone.sdk.vmodelx.player.AutelPlayerView"
            )
            var playerViewObj: View? = null
            for (clsName in viewClassNames) {
                try {
                    val cls = Class.forName(clsName)
                    val ctor = cls.getConstructor(android.content.Context::class.java)
                    playerViewObj = ctor.newInstance(this) as? View
                    if (playerViewObj != null) break
                } catch (_: Exception) {}
            }

            val playerCls = Class.forName("com.autel.player.player.AutelPlayer")
            val playerCtor = playerCls.constructors.firstOrNull { it.parameterTypes.size == 1 }
            val playerObj = playerCtor?.newInstance(0) ?: playerCls.getConstructor().newInstance()

            if (playerViewObj != null) {
                container.removeAllViews()
                container.addView(
                    playerViewObj,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                playerCls.methods.firstOrNull { it.name == "addVideoView" && it.parameterTypes.size == 1 }
                    ?.invoke(playerObj, playerViewObj)
            }

            pm.javaClass.methods.firstOrNull { it.name == "addAutelPlayer" && it.parameterTypes.size == 1 }
                ?.invoke(pm, playerObj)
            playerCls.methods.firstOrNull { it.name == "startPlayer" }?.invoke(playerObj)
        } catch (_: Exception) {}
    }

    private fun initDrone() {
        try {
            SDKManager.get().init(applicationContext, true)
            startVideoFeed()
        } catch (_: Exception) {}
        val loop = object : Runnable {
            override fun run() {
                try {
                    val drone = DeviceManager.getDeviceManager().getFirstDroneDevice()
                    if (drone != null && drone.isConnected()) {
                        if (!isDroneConnected) {
                            isDroneConnected = true
                            connectedDrone = drone
                            startVideoFeed()
                            speak("Drone connected")
                        }
                        val state = drone.getDeviceStateData()
                        val flight = state?.flightControlData
                        if (flight != null) {
                            droneLat = flight.droneLatitude
                            droneLon = flight.droneLongitude
                            droneRelAlt = flight.altitude.toDouble()

                            val att = getProp(flight, "getDroneAttitude")
                            val y = (getProp(att, "getYaw") as? Number)?.toDouble()
                                ?: (getProp(flight, "getYaw") as? Number)?.toDouble()
                            if (y != null) droneYaw = (y + 360.0) % 360.0

                            val home = getProp(flight, "getHomeLocation")
                            val hAlt = (getProp(home, "getAltitude") as? Number)?.toDouble()
                            if (hAlt != null && hAlt != 0.0) takeoffGroundAmsl = hAlt
                        }
                        val gimbal = getProp(state, "getGimbalInfo") ?: getProp(state, "getGimbalData")
                        val p = (getProp(gimbal, "getPitch") as? Number)?.toDouble()
                        if (p != null) gimbalPitch = p

                        val bat = getProp(state, "getBatteryInfo") ?: getProp(state, "getBatteryData")
                        val b = (getProp(bat, "getRemainingPowerPercent") as? Number)?.toInt()
                        if (b != null) tvDroneBattery?.text = "DRONE: $b%"

                        updateUI()
                    } else {
                        isDroneConnected = false
                        connectedDrone = null
                        tvZoneInfo?.text = "EDS | DRONE: OFF | OT: ---°"
                    }
                } catch (_: Exception) {}
                handler.postDelayed(this, 500)
            }
        }
        handler.post(loop)
    }

    private fun getDir(deg: Double): String {
        val d = (deg + 360.0) % 360.0
        return when {
            d >= 337.5 || d < 22.5 -> "N"
            d < 67.5 -> "NE"
            d < 112.5 -> "E"
            d < 157.5 -> "SE"
            d < 202.5 -> "S"
            d < 247.5 -> "SW"
            d < 292.5 -> "W"
            else -> "NW"
        }
    }

    private fun getTargetGrid(): Pair<Double, Double> {
        val crs = CRSFactory()
        val zone = ((droneLon + 180) / 6).toInt() + 1
        val utm = crs.createFromParameters("UTM", "+proj=utm +zone=$zone +datum=WGS84 +units=m +no_defs")
        val tf = CoordinateTransformFactory().createTransform(crs.createFromName("EPSG:4326"), utm)
        val dst = ProjCoordinate()
        tf.transform(ProjCoordinate(droneLon, droneLat), dst)

        if (gimbalPitch <= -89.0) return Pair(dst.x, dst.y)
        val rad = Math.toRadians(-gimbalPitch.coerceIn(-89.0, -5.0))
        val dist = if (droneRelAlt > 1.0) droneRelAlt / tan(rad) else 0.0
        val yawRad = Math.toRadians(droneYaw)
        return Pair(dst.x + dist * sin(yawRad), dst.y + dist * cos(yawRad))
    }

    private fun updateUI() {
        val hdg = droneYaw.roundToInt()
        tvZoneInfo?.text = "EDS | DRONE: CONNECTED | OT: ${if (hdg == 0) 360 else hdg}°(${getDir(droneYaw)})"
        if (droneLat == 0.0 || droneLon == 0.0) return

        val (tE, tN) = getTargetGrid()
        tvLiveDroneGrid?.text = "TGT E: ${tE.roundToInt()} | N: ${tN.roundToInt()} | H: ${takeoffGroundAmsl.roundToInt()}m (${gimbalPitch.roundToInt()}°)"
        if (isTargetLocked) {
            tvDistanceInfo?.text = "DIST: ${hypot(tE - lockedEasting, tN - lockedNorthing).roundToInt()}m"
        }
    }
    private fun setupButtons() {
        findViewById<Button>(R.id.btn_lock_target)?.setOnClickListener { lockTarget() }
        findViewById<Button>(R.id.btn_record_shot)?.setOnClickListener { recordShot() }
        findViewById<Button>(R.id.btn_rth)?.setOnClickListener { sendRth() }
        btnVoice?.setOnClickListener {
            isVoiceListening = !isVoiceListening
            btnVoice?.text = if (isVoiceListening) "MIC: ON" else "MIC: OFF"
            if (isVoiceListening) startListening() else speechRecognizer?.stopListening()
        }
    }

    private fun lockTarget() {
        if (droneLat == 0.0 || droneLon == 0.0) {
            speak("Waiting for drone GPS")
            return
        }
        val (tE, tN) = getTargetGrid()
        lockedEasting = tE
        lockedNorthing = tN
        isTargetLocked = true
        shotCount = 0
        tvSavedEasting?.text = "Easting : ${tE.roundToInt()}"
        tvSavedNorthing?.text = "Northing: ${tN.roundToInt()}"
        tvSavedHeight?.text = "Height  : ${takeoffGroundAmsl.roundToInt()} m (${gimbalPitch.roundToInt()}°)"
        tvFos1?.text = "S1: Ready"
        tvFos2?.text = "S2: Ready"
        tvFfeStatus?.text = "ADJUSTING FIRE..."
        tvFfeStatus?.setTextColor(0xFFFFC107.toInt())
        speak("Target locked")
    }

    private fun recordShot() {
        if (!isTargetLocked) {
            speak("Lock target first")
            return
        }
        val (sE, sN) = getTargetGrid()
        val dE = sE - lockedEasting
        val dN = sN - lockedNorthing
        val err = hypot(dE, dN)
        shotCount++

        val top = "S$shotCount E:${sE.roundToInt()} N:${sN.roundToInt()}"
        if (err <= 20.0) {
            speak("Target hit! Fire for effect!")
            tvFfeStatus?.text = "FIRE FOR EFFECT! (${err.roundToInt()}m)"
            tvFfeStatus?.setTextColor(0xFF00E676.toInt())
            if (shotCount % 2 == 1) tvFos1?.text = "$top (HIT)" else tvFos2?.text = "$top (HIT)"
        } else {
            val lr = if (dE >= 0) "LEFT ${abs(dE).roundToInt()}" else "RIGHT ${abs(dE).roundToInt()}"
            val ad = if (dN >= 0) "DROP ${abs(dN).roundToInt()}" else "ADD ${abs(dN).roundToInt()}"
            speak("Shot $shotCount. $lr meters, $ad meters")
            tvFfeStatus?.text = "$lr | $ad"
            tvFfeStatus?.setTextColor(0xFF40C4FF.toInt())
            if (shotCount % 2 == 1) tvFos1?.text = top else tvFos2?.text = top
        }
    }

    private fun sendRth() {
        val km = connectedDrone?.getKeyManager()
        if (isDroneConnected && km != null) {
            try {
                val keyField = FlightControlKey::class.java.fields.firstOrNull {
                    it.name.contains("GoHome", ignoreCase = true) || it.name.contains("Rth", ignoreCase = true)
                }
                val rawKey = keyField?.get(null)
                if (rawKey != null) {
                    val createMethod = KeyTools::class.java.methods.firstOrNull {
                        it.name == "createKey" && it.parameterTypes.size == 1
                    }
                    val key = createMethod?.invoke(null, rawKey)
                    val performMethod = km.javaClass.methods.firstOrNull {
                        it.name == "performAction" && it.parameterTypes.size == 3
                    }
                    if (key != null && performMethod != null) {
                        performMethod.invoke(km, key, null, object : CommonCallbacks.CompletionCallbackWithParam<Any> {
                            override fun onSuccess(t: Any?) { speak("Returning to home") }
                            override fun onFailure(code: IAutelCode, msg: String?) { speak("RTH failed") }
                        })
                        return
                    }
                }
                speak("RTH key not supported")
            } catch (_: Exception) {
                speak("RTH command error")
            }
        } else {
            speak("Drone not connected")
        }
    }

    private fun initVoice() {
        tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) tts?.language = Locale.US }
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(p: Float) {}
                override fun onBufferReceived(p: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(p: Bundle?) {}
                override fun onEvent(p: Int, b: Bundle?) {}
                override fun onError(e: Int) { if (isVoiceListening) handler.postDelayed({ startListening() }, 800) }
                override fun onResults(r: Bundle?) {
                    val txt = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.lowercase() ?: ""
                    when {
                        txt.contains("lock") || txt.contains("target") -> lockTarget()
                        txt.contains("record") || txt.contains("shot") -> recordShot()
                        txt.contains("return") || txt.contains("home") || txt.contains("rth") -> sendRth()
                        txt.contains("height") || txt.contains("altitude") -> speak("Height ${droneRelAlt.roundToInt()} meters")
                    }
                    if (isVoiceListening) handler.postDelayed({ startListening() }, 800)
                }
            })
            startListening()
        }
    }

    private fun startListening() {
        if (!isVoiceListening) return
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US)
        }
        try { speechRecognizer?.startListening(i) } catch (_: Exception) {}
    }

    private fun speak(msg: String) { tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, null) }

    private fun checkPermissions() {
        val p = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT)
        val need = p.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) ActivityCompat.requestPermissions(this, need.toTypedArray(), 101)
    }

    override fun onDestroy() {
        super.onDestroy()
        isVoiceListening = false
        handler.removeCallbacksAndMessages(null)
        speechRecognizer?.destroy()
        tts?.shutdown()
        AutelPlayerManager.getInstance().release()
    }
}
