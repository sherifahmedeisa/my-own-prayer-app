package com.prayer.widget

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MosquesMapActivity : Activity(), SensorEventListener {

    private lateinit var webView: WebView
    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private var userLat: Double = PrayerConfig.LAT
    private var userLng: Double = PrayerConfig.LNG
    private var nearestMosque: NearbyMosque? = null
    private var currentHeading: Float = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mosques_map)

        // Read location from intent or PrayerRepository
        userLat = intent.getDoubleExtra("EXTRA_LAT", PrayerRepository.getLatitude(this))
        userLng = intent.getDoubleExtra("EXTRA_LNG", PrayerRepository.getLongitude(this))

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        setupUI()
        loadNearestMosques()
    }

    private fun setupUI() {
        val sp = getSharedPreferences("prayer_cache", Context.MODE_PRIVATE)
        val isCream = (sp.getString("theme", "dark") == "cream")

        findViewById<View>(R.id.map_header)?.setBackgroundColor(
            if (isCream) Color.parseColor("#FAF5EA") else Color.parseColor("#0D1512")
        )
        findViewById<TextView>(R.id.tv_map_title)?.setTextColor(
            if (isCream) Color.parseColor("#8A6F3A") else Color.parseColor("#F5C030")
        )
        findViewById<Button>(R.id.btn_back)?.setOnClickListener { finish() }

        webView = findViewById(R.id.webview_map)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                updateMapMarkers()
            }
        }

        findViewById<Button>(R.id.btn_open_external_nav)?.setOnClickListener {
            nearestMosque?.let { m ->
                launchOfflineNavigation(m.lat, m.lng, m.name)
            }
        }
    }

    private fun loadNearestMosques() {
        Thread {
            val list = MosqueRepository.findNearestMosques(this, userLat, userLng, limit = 5)
            nearestMosque = list.firstOrNull()

            runOnUiThread {
                if (nearestMosque != null) {
                    val m = nearestMosque!!
                    findViewById<TextView>(R.id.tv_nearest_name)?.text = m.name
                    findViewById<TextView>(R.id.tv_nearest_dist)?.text = "🚶 ${m.distanceMeters}m · ~${m.walkMinutes} min walk"
                    findViewById<TextView>(R.id.tv_nearest_sub)?.text = "Nearest Mosque · أقرب مسجد إليك"
                    findViewById<View>(R.id.card_nearest_summary)?.visibility = View.VISIBLE
                }

                // Load interactive offline leaflet map template
                val mapHtml = buildMapHtml(userLat, userLng, list)
                webView.loadDataWithBaseURL("file:///android_asset/", mapHtml, "text/html", "utf-8", null)
            }
        }.start()
    }

    private fun updateMapMarkers() {
        nearestMosque?.let { m ->
            webView.evaluateJavascript("if (window.focusMosque) focusMosque(${m.lat}, ${m.lng});", null)
        }
    }

    private fun launchOfflineNavigation(lat: Double, lng: Double, label: String) {
        val geoUri = Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(label)})")
        val mapIntent = Intent(Intent.ACTION_VIEW, geoUri)

        // Try offline apps first (Organic Maps, OsmAnd, Google Maps)
        val chooser = Intent.createChooser(mapIntent, "Navigate to $label")
        try {
            startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(this, "No navigation app found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildMapHtml(uLat: Double, uLng: Double, mosques: List<NearbyMosque>): String {
        val sp = getSharedPreferences("prayer_cache", Context.MODE_PRIVATE)
        val isCream = (sp.getString("theme", "dark") == "cream")

        val bgHex = if (isCream) "#FAF5EA" else "#080B09"
        val goldHex = if (isCream) "#8A6F3A" else "#F5C030"
        val cardHex = if (isCream) "#F5EFE1" else "#131E19"
        val textHex = if (isCream) "#3A2E1A" else "#F4F7F5"

        val mosquesJsonArray = mosques.joinToString(",") { m ->
            """{"id":${m.id},"name":"${m.name.replace("\"", "\\\"")}","lat":${m.lat},"lng":${m.lng},"dist":${m.distanceMeters},"walk":${m.walkMinutes}}"""
        }

        return """
        <!DOCTYPE html>
        <html>
        <head>
          <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
          <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
          <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
          <style>
            html, body { height:100%; margin:0; padding:0; background:$bgHex; font-family:-apple-system, sans-serif; }
            #map { width:100%; height:100%; background:$bgHex; }
            .leaflet-popup-content-wrapper {
              background: $cardHex;
              color: $textHex;
              border: 1px solid $goldHex;
              border-radius: 12px;
              font-family: inherit;
              box-shadow: 0 8px 24px rgba(0,0,0,0.5);
            }
            .leaflet-popup-tip { background: $cardHex; }
            .popup-title { font-weight: bold; font-size: 15px; color: $goldHex; margin-bottom: 4px; }
            .popup-dist { font-size: 12px; opacity: 0.9; }
            .radar-indicator {
              width: 18px; height: 18px; background: #3b82f6; border: 3px solid white;
              border-radius: 50%; box-shadow: 0 0 12px #3b82f6;
            }
            .mosque-pin {
              background: $goldHex; color: #080B09; border-radius: 50%;
              width: 28px; height: 28px; display: flex; align-items: center; justify-content: center;
              font-size: 14px; font-weight: bold; border: 2px solid white; box-shadow: 0 4px 12px rgba(0,0,0,0.4);
            }
            .mosque-pin.nearest {
              background: #10b981; animation: pulse 2s infinite;
            }
            @keyframes pulse {
              0% { box-shadow: 0 0 0 0 rgba(16, 185, 129, 0.7); }
              70% { box-shadow: 0 0 0 15px rgba(16, 185, 129, 0); }
              100% { box-shadow: 0 0 0 0 rgba(16, 185, 129, 0); }
            }
          </style>
        </head>
        <body>
          <div id="map"></div>
          <script>
            const userLat = $uLat;
            const userLng = $uLng;
            const mosques = [$mosquesJsonArray];

            const map = L.map('map', { zoomControl: false }).setView([userLat, userLng], 16);

            L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
              maxZoom: 19,
              attribution: '© OpenStreetMap · GIS Egypt'
            }).addTo(map);

            // User location marker
            const userIcon = L.divIcon({ className: 'radar-indicator', iconSize: [18, 18] });
            L.marker([userLat, userLng], { icon: userIcon })
              .addTo(map)
              .bindPopup('<div class="popup-title">📍 Your Location</div><div class="popup-dist">موقعك الحالي</div>');

            // Draw line to nearest mosque (road line-of-sight indicator)
            if (mosques.length > 0) {
              const nearest = mosques[0];
              const pathLine = L.polyline([[userLat, userLng], [nearest.lat, nearest.lng]], {
                color: '$goldHex',
                weight: 4,
                dashArray: '8, 8',
                opacity: 0.9
              }).addTo(map);
            }

            mosques.forEach((m, idx) => {
              const isNearest = idx === 0;
              const iconClass = isNearest ? 'mosque-pin nearest' : 'mosque-pin';
              const icon = L.divIcon({
                className: iconClass,
                html: '🕌',
                iconSize: [28, 28]
              });

              const marker = L.marker([m.lat, m.lng], { icon: icon }).addTo(map);
              marker.bindPopup(
                '<div class="popup-title">' + m.name + '</div>' +
                '<div class="popup-dist">🚶 ' + m.dist + ' meters (~' + m.walk + ' mins walk)</div>'
              );

              if (isNearest) {
                marker.openPopup();
              }
            });

            window.focusMosque = function(lat, lng) {
              map.setView([lat, lng], 17);
            };
          </script>
        </body>
        </html>
        """.trimIndent()
    }

    override fun onResume() {
        super.onResume()
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || nearestMosque == null) return
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            val orientation = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientation)
            val azimuthDeg = (Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0
            currentHeading = azimuthDeg.toFloat()

            // Calculate relative bearing to point compass arrow directly at nearest mosque
            val bearingToMosque = nearestMosque!!.bearingDegrees
            val relativeBearing = (bearingToMosque - currentHeading + 360f) % 360f

            val arrowView = findViewById<ImageView>(R.id.img_compass_arrow)
            arrowView?.rotation = relativeBearing
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
