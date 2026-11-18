package com.artillery.eds

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.locationtech.proj4j.CRSFactory
import org.locationtech.proj4j.CoordinateTransformFactory
import org.locationtech.proj4j.ProjCoordinate
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var tvSavedEasting: TextView
    private lateinit var tvSavedNorthing: TextView
    private lateinit var tvSavedHeight: TextView
    private lateinit var tvFos1: TextView
    private lateinit var tvFos2: TextView
    private lateinit var tvDroneBattery: TextView
    private lateinit var tvRcBattery: TextView
    private lateinit var tvDistanceInfo: TextView

    private var lockedEasting: Double = 0.0
    private var lockedNorthing: Double = 0.0
    private var isTargetLocked = false
    private var shotCount = 0

    private var currentDroneLat = 26.9124
    private var currentDroneLon = 75.7873
    private var currentDroneAlt = 120.5

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        checkPermissions()
        setupButtons()
    }

    private fun initViews() {
        tvSavedEasting = findViewById(R.id.tv_saved_easting)
        tvSavedNorthing = findViewById(R.id.tv_saved_northing)
        tvSavedHeight = findViewById(R.id.tv_saved_height)
        tvFos1 = findViewById(R.id.tv_fos_1)
        tvFos2 = findViewById(R.id.tv_fos_2)
        tvDroneBattery = findViewById(R.id.tv_drone_battery)
        tvRcBattery = findViewById(R.id.tv_rc_battery)
        tvDistanceInfo = findViewById(R.id.tv_distance_info)
    }

    private fun setupButtons() {
        val btnLockTarget: Button = findViewById(R.id.btn_lock_target)
        val btnRecordShot: Button = findViewById(R.id.btn_record_shot)
        val btnRth: Button = findViewById(R.id.btn_rth)

        btnLockTarget.setOnClickListener {
            val grid = convertLatLonToGrid(currentDroneLat, currentDroneLon)
            lockedEasting = grid.x
            lockedNorthing = grid.y
            isTargetLocked = true

            tvSavedEasting.text = "Easting : ${lockedEasting.roundToInt()}"
            tvSavedNorthing.text = "Northing: ${lockedNorthing.roundToInt()}"
            tvSavedHeight.text = "Height  : ${currentDroneAlt.roundToInt()} m"
            Toast.makeText(this, "Target Locked!", Toast.LENGTH_SHORT).show()
        }

        btnRecordShot.setOnClickListener {
            if (!isTargetLocked) {
                Toast.makeText(this, "Pehle Target Lock karein!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val shotGrid = convertLatLonToGrid(currentDroneLat, currentDroneLon)
            val diffE = (shotGrid.x - lockedEasting).roundToInt()
            val diffN = (shotGrid.y - lockedNorthing).roundToInt()

            shotCount++
            val resultText = "E: ${if (diffE >= 0) "+$diffE" else diffE}m | N: ${if (diffN >= 0) "+$diffN" else diffN}m"

            if (shotCount % 2 == 1) {
                tvFos1.text = "Shot $shotCount: $resultText"
            } else {
                tvFos2.text = "Shot $shotCount: $resultText"
            }
        }

        btnRth.setOnClickListener {
            Toast.makeText(this, "RTH Command Sent to Drone!", Toast.LENGTH_LONG).show()
        }
    }

    private fun convertLatLonToGrid(lat: Double, lon: Double): ProjCoordinate {
        val crsFactory = CRSFactory()
        val wgs84 = crsFactory.createFromName("EPSG:4326")
        val zone = ((lon + 180) / 6).toInt() + 1
        val utm = crsFactory.createFromParameters("UTM", "+proj=utm +zone=$zone +datum=WGS84 +units=m +no_defs")

        val ctFactory = CoordinateTransformFactory()
        val transform = ctFactory.createTransform(wgs84, utm)

        val srcCoord = ProjCoordinate(lon, lat)
        val dstCoord = ProjCoordinate()
        transform.transform(srcCoord, dstCoord)
        return dstCoord
    }

    private fun checkPermissions() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BLUETOOTH_CONNECT
        )
        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 101)
        }
    }
}
