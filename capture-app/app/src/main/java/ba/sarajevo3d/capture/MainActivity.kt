package ba.sarajevo3d.capture

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.provider.MediaStore
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.SharedPreferences

/**
 * Sarajevo 3D capture, version 0.1.
 *
 * One tap = one photo. The phone's GPS position is written into each photo
 * (EXIF). Photos go to Pictures/Sarajevo3D/<date>/, one folder per day, ready
 * to drop into OpenDroneMap / WebODM.
 *
 * Every photo is also put on a waiting list (UploadQueue). When the phone is on
 * Wi-Fi, a background job sends the list to the Sarajevo 3D server on your PC,
 * which builds the 3D models. Works also when the app is closed.
 *
 * No API keys and no Google services: CameraX for the camera and Android's
 * built-in LocationManager for GPS.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var shutterButton: Button

    private var imageCapture: ImageCapture? = null
    private lateinit var locationManager: LocationManager
    private var lastLocation: Location? = null

    private var photoCount = 0
    private var lastSaved = ""
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { updateStatus() }
    }

    // Full objects (not lambdas): on Android 10 the framework still calls the
    // other LocationListener methods, and a lambda would crash there.
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastLocation = location
            updateStatus()
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val networkListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // Only use network location until a GPS fix arrives.
            if (lastLocation?.provider != LocationManager.GPS_PROVIDER) {
                lastLocation = location
                updateStatus()
            }
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.CAMERA] == true) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission is needed", Toast.LENGTH_LONG).show()
            }
            if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
                startLocation()
            } else {
                Toast.makeText(this, "Without location, photos have no GPS", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.preview)
        statusText = findViewById(R.id.status)
        shutterButton = findViewById(R.id.shutter)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        shutterButton.setOnClickListener { takePhoto() }
        findViewById<Button>(R.id.server).setOnClickListener { askServer() }
        findViewById<Button>(R.id.open_map).setOnClickListener {
            val server = serverUrl()
            if (server.isEmpty()) askServer()
            else startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(server)))
        }

        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
        updateStatus()
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            // Full resolution, best quality: photogrammetry needs sharp detail.
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setJpegQuality(95)
                .build()
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return
        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 500L, 0f, locationListener, Looper.getMainLooper()
            )
        } else {
            Toast.makeText(this, "Turn on GPS / Location in settings", Toast.LENGTH_LONG).show()
        }
        if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER, 2000L, 0f, networkListener, Looper.getMainLooper()
            )
        }
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val folder = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val name = "IMG_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Sarajevo3D/$folder")
        }
        val metadata = ImageCapture.Metadata().apply {
            location = lastLocation // written into EXIF as GPS latitude/longitude/altitude
        }
        val options = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ).setMetadata(metadata).build()

        shutterButton.isEnabled = false
        capture.takePicture(options, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    shutterButton.isEnabled = true
                    photoCount++
                    lastSaved = "Pictures/Sarajevo3D/$folder"
                    output.savedUri?.let { UploadQueue.add(this@MainActivity, it.toString()) }
                    previewView.animate().alpha(0.3f).setDuration(80)
                        .withEndAction { previewView.animate().alpha(1f).setDuration(80) }
                    updateStatus()
                }

                override fun onError(exception: ImageCaptureException) {
                    shutterButton.isEnabled = true
                    Toast.makeText(this@MainActivity, "Photo failed: ${exception.message}",
                        Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun updateStatus() {
        val loc = lastLocation
        val gps = if (loc == null) {
            "No GPS yet, wait outside a moment"
        } else {
            val acc = loc.accuracy.toInt()
            val warn = if (acc > 15) "  (weak, wait or move to open sky)" else ""
            String.format(
                Locale.US, "GPS %.6f, %.6f  ±%d m%s", loc.latitude, loc.longitude, acc, warn
            )
        }
        val count = if (photoCount == 0) {
            "Tap the button to take a photo"
        } else {
            "$photoCount photos this session, saved in $lastSaved"
        }
        val settings = getSharedPreferences("settings", MODE_PRIVATE)
        val waiting = UploadQueue.count(this)
        val up = when {
            serverUrl().isEmpty() -> "\nNo server set: photos only saved on the phone ($waiting waiting)"
            waiting > 0 -> "\n$waiting photos waiting, sent automatically on Wi-Fi"
            else -> "\nAll photos sent"
        } + (settings.getString("last_place", null)?.let { "\n$it" } ?: "")
        statusText.text = "$gps\n$count$up"
    }

    // ---- server --------------------------------------------------------------

    private fun serverUrl(): String =
        getSharedPreferences("settings", MODE_PRIVATE).getString("server", "")!!.trimEnd('/')

    private fun askServer() {
        val input = EditText(this).apply {
            setText(serverUrl().ifEmpty { "http://192.168.1.20:8000" })
        }
        AlertDialog.Builder(this)
            .setTitle("Server address")
            .setMessage("Address of the Sarajevo 3D server on your PC (shown when you start it). Leave empty to only save photos on the phone.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                var url = input.text.toString().trim()
                if (url.isNotEmpty() && !url.startsWith("http")) url = "http://$url"
                getSharedPreferences("settings", MODE_PRIVATE).edit().putString("server", url).apply()
                UploadQueue.schedule(this)
                updateStatus()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        UploadQueue.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
        getSharedPreferences("settings", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(prefsListener)
        if (UploadQueue.count(this) > 0) UploadQueue.schedule(this) // try again now
        updateStatus()
    }

    override fun onPause() {
        super.onPause()
        UploadQueue.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        getSharedPreferences("settings", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        locationManager.removeUpdates(locationListener)
        locationManager.removeUpdates(networkListener)
    }
}
