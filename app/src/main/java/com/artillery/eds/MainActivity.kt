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
    private var tvSavedEasting: TextView? = null
    private var tvSavedNorthing: TextView? = null
    private var tvSavedHeight: TextView? = null
    private var tvFos1: TextView? = null
    private var tvFos2: TextView? = null
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
    private var connectedDrone: IAutelDroneDevice? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isVoiceListening = true
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        autelPlayerView = findViewById(R.id.autel_player_view)
        tvZoneInfo = findViewById(R.id.tv_zone_info)
        tvLiveAltitude = findViewById(R.id.tv_live_altitude)
        tvDistanceInfo = findViewById(R.id.tv_distance_info)
        tvDroneBattery = findViewById(R.id.tv_drone_battery)
        tvSavedEasting = findViewById(R.id.tv_saved_easting)
        tvSavedNorthing = findViewById(R.id.tv_saved_northing)
        tvSavedHeight = findViewById(R.id.tv_saved_height)
        tvFos1 = findViewById(R.id.tv_fos_1)
        tvFos2 = findViewById(R.id.tv_fos_2)
        tvLiveDroneGrid = findViewById(R.id.tv_live_drone_grid)
        btnVoice = findViewById(R.id.btn_voice)

        checkPermissions()
        setupButtons()
        initVoice()
        initDrone()
    }

    private fun getProp(obj: Any?, name: String): Any? {
        if (obj == null) return null
        return try {
            val m = obj.javaClass.getMethod(name)
            m.invoke(obj)
        } catch (_: Exception) { null }
    }

    private fun initDrone() {
        try {
            SDKManager.get().init(applicationContext, true)
            autelPlayerView?.let {
                AutelPlayerManager.getInstance().init(this, false)
                AutelPlayerManager.getInstance().addAutelPlayer(it)
            }
        } catch (_: Exception) {}

        val loop = object : Runnable {
            override fun run() {
                try {
                    val drone = DeviceManager.getDeviceManager().getFirstDroneDevice()
                    if (drone != null && drone.isConnected()) {
                        if (!isDroneConnected) {
                            isDroneConnected = true
                            connectedDrone = drone
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
                        tvZoneInfo?.text = "OT:360° | OFF | HDG:---°"
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
        tvZoneInfo?.text = "OT:360° | HDG:${if (hdg == 0) 360 else hdg}°(${getDir(droneYaw)})"
        if (droneLat == 0.0 || droneLon == 0.0) return

        val (tE, tN) = getTargetGrid()
        tvLiveAltitude?.text = "ALT: ${droneRelAlt.roundToInt()}m"
        tvLiveDroneGrid?.text = "TGT E:${tE.roundToInt()} N:${tN.roundToInt()} | HT:${takeoffGroundAmsl.roundToInt()}m"
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
        tvSavedHeight?.text = "Tgt Ht  : ${takeoffGroundAmsl.roundToInt()}m"
        tvFos1?.text = "Shot 1: Ready"
        tvFos2?.text = "Shot 2: Ready"
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
        val bot = if (err <= 20.0) {
            speak("Target hit! Fire for effect!")
            "TARGET HIT! FIRE FOR EFFECT (${err.roundToInt()}m)"
        } else {
